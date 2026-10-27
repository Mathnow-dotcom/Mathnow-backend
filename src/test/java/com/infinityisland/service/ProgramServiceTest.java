package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.dao.user.User;
import com.infinityisland.util.ProgressDefaults;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.infinityisland.service.ProgramProgressionRules.*;

class ProgramServiceTest {
    void additionCurriculum() {
        when(config.getMaxLevel("add")).thenReturn(19);
        when(mongo.find(any(Query.class), eq(Catalog.class))).thenAnswer(x -> {
            List<Catalog> catalogs = new ArrayList<>();
            for (int l=1;l<=19;l++) for (String belt : com.infinityisland.model.Belt.COLORED_ORDER) {
                Catalog c = new Catalog(); c.setLevel(""+l); c.setBelt(belt);
                Catalog.Fact f = new Catalog.Fact(); f.setA(l); f.setB(0); c.setFacts(List.of(f)); catalogs.add(c);
            }
            return catalogs;
        });
    }
    @Test void newAdditionRequiresPartOneTestBeforeLevelEleven() {
        additionCurriculum();
        assertEquals(10, service.visibleMax("student", "add"));
        for (int l=1;l<=10;l++) complete("add",l);
        assertEquals(Status.ASSESSMENT_READY, service.requireAssessment("student","add").status());
        assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> service.requireLearning("student","add",11));
        AssessmentAttempt a = result(true);
        // Old full-Addition records must not accidentally satisfy a new part test.
        assessment.set(a);
        assertEquals(Status.ASSESSMENT_READY, service.refresh("student").subjects.get("add").status());
        a.programEnrollment = service.assessmentEnrollment(stored.get(), stored.get().subjects.get("add"));
        assertEquals(11, service.refresh("student").subjects.get("add").currentLevel());
        assertEquals("add", stored.get().activeSubject);
        assertEquals(11, service.contentLevel("student","add",11));
        assertEquals(19, service.visibleMax("student","add"));
        assertFalse(service.unlocked("student", "sub"));
    }
    @Test void existingFirstCycleAdditionKeepsLevelSeventeenAndHistory() {
        additionCurriculum();
        user.setIntegratedEnrollment(null);
        for (int l=1;l<=16;l++) complete("add",l);
        var old = importCurrentSubject("add", new Curriculum(java.util.stream.IntStream.rangeClosed(1,19).boxed().toList()),
            new HashSet<>(java.util.stream.IntStream.rangeClosed(1,16).boxed().toList()));
        ProgramProgress p = new ProgramProgress(); p.id="student"; p.activeSubject="add"; p.subjects.put("add", old);
        stored.set(p);
        var migrated = service.get("student").subjects.get("add");
        assertEquals(2, migrated.curriculum().part(migrated.batch()));
        assertEquals(17, migrated.currentLevel());
        assertEquals(17, service.contentLevel("student","add",17));
        assertEquals(migrated, service.get("student").subjects.get("add"));
        verify(mongo,times(1)).save(any(ProgramProgress.class));
        verify(users,never()).save(any());
    }
    @Test void oldRepeatedAdditionPreservesOriginalLevelMappingAndAllocatesFreshPartTwo() {
        additionCurriculum();
        Curriculum old = new Curriculum(java.util.stream.IntStream.rangeClosed(1,19).boxed().toList());
        for (int l=1;l<=25;l++) complete("add",l);
        State original = new State("add",old,2,Status.LEARNING,
            new HashSet<>(java.util.stream.IntStream.rangeClosed(1,25).boxed().toList()),Map.of());
        ProgramProgress p = new ProgramProgress(); p.id="student"; p.activeSubject="add"; p.subjects.put("add", original);
        stored.set(p);
        State migrated = service.get("student").subjects.get("add");
        assertEquals(27, migrated.currentLevel());
        assertEquals(1, service.contentLevel("student","add",20));
        assertEquals(11, service.contentLevel("student","add",27));
        assertTrue(migrated.completedLevels().contains(25));
        assertEquals(125, migrated.curriculum().lastLevel());
    }
    @Test void legacyFractionStudentsStayInFractionsEvenAfterMigration() {
        service.get("student"); // Simulate an already-created integrated record.
        user.setIntegratedEnrollment(null);
        when(config.isOperationEnabled("frac")).thenReturn(true);
        var fractions = new LinkedHashMap<String,Object>();
        fractions.put("L6", ProgressDefaults.createLevelNode(6, true));
        user.getProgress().put("frac", fractions);
        assertEquals("frac", service.view("student").get("subject"));
        assertEquals(6, service.view("student").get("level"));
        assertNull(service.view("student").get("batch"));
        assertEquals(Map.of("operation", "frac", "level", 6), service.project("student", user.getProgress()).get("_current"));
        assertEquals("add", stored.get().activeSubject, "Do not rewrite historical program state");
        assertFalse(fractions.containsKey("L7"));
    }
    @Test void newStudentsDoNotSkipToFractionsAndDivisionGraduatesContinueThere() {
        when(config.isOperationEnabled("frac")).thenReturn(true);
        assertEquals("add", service.view("student").get("subject"));
        stored.get().activeSubject = null;
        assertEquals("frac", service.view("student").get("subject"));
        assertEquals("LEARNING", service.view("student").get("status"));
        assertEquals(1, service.view("student").get("level"));
    }
    @Test void projectionUsesActiveBatchRatherThanLaterLegacyUnlocks() {
        service.get("student"); complete("add", 1); complete("add", 2);
        assessment.set(result(false));
        var projected = service.project("student", user.getProgress());
        assertEquals(Map.of("operation", "add", "level", 3), projected.get("_current"));
        assertFalse(user.getProgress().containsKey("_current"), "Display metadata must not alter stored progress");
    }
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final CachedUserService users = mock(CachedUserService.class);
    final GameConfigService config = mock(GameConfigService.class);
    final ProgramService service = new ProgramService(mongo, users, config);
    final User user = new User();
    final AtomicReference<ProgramProgress> stored = new AtomicReference<>();
    final AtomicReference<AssessmentAttempt> assessment = new AtomicReference<>();
    ProgramServiceTest() {
        user.setId("student"); user.setPin("1234"); user.setIntegratedEnrollment(true);
        user.setProgress(ProgressDefaults.baselineProgress());
        when(users.findById("student")).thenReturn(Optional.of(user));
        when(mongo.findById("student", User.class)).thenReturn(user);
        when(mongo.findById("student", ProgramProgress.class)).thenAnswer(x -> stored.get());
        when(mongo.insert(any(ProgramProgress.class))).thenAnswer(x -> { stored.set(x.getArgument(0)); return stored.get(); });
        when(mongo.save(any(ProgramProgress.class))).thenAnswer(x -> { stored.set(x.getArgument(0)); return stored.get(); });
        when(config.getMaxLevel(anyString())).thenReturn(2);
        when(mongo.find(any(Query.class), eq(Catalog.class))).thenAnswer(x -> {
            List<Catalog> result = new ArrayList<>();
            for (int i=1;i<=2;i++) for (String belt : com.infinityisland.model.Belt.COLORED_ORDER) {
                Catalog c = new Catalog(); c.setLevel(""+i); c.setBelt(belt);
                Catalog.Fact f = new Catalog.Fact(); f.setA(i); f.setB(1); c.setFacts(List.of(f)); result.add(c);
            }
            return result;
        });
        when(mongo.findOne(any(Query.class), eq(AssessmentAttempt.class))).thenAnswer(x -> {
            AssessmentAttempt a = assessment.get();
            if (a == null) return null;
            var q = ((Query)x.getArgument(0)).getQueryObject();
            return Objects.equals(q.get("programSubject"), a.programSubject) &&
                Objects.equals(q.get("programEnrollment"), a.programEnrollment) &&
                Objects.equals(q.get("programBatch"), a.programBatch) ? a : null;
        });
    }
    @SuppressWarnings("unchecked") void complete(String op, int level) {
        Map<String,Object> levels = (Map<String,Object>)user.getProgress().computeIfAbsent(op, k -> new LinkedHashMap<>());
        Map<String,Object> node = ProgressDefaults.createLevelNode(level, true);
        node.put("completed", true); levels.put("L"+level, node);
    }
    AssessmentAttempt result(boolean pass) {
        var s = service.refresh("student").subjects.get("add");
        AssessmentAttempt a = new AssessmentAttempt(); a.id = "assessment"; a.completed = true;
        a.programSubject = "add"; a.programBatch = s.batch();
        a.programEnrollment = stored.get().enrollment;
        a.items = List.of(new AssessmentAttempt.Item(1,1));
        a.answers = List.of(new AssessmentAttempt.Answer("1 + 1", "2", pass, 2999));
        return a;
    }
    @Test void newStudentsAreSequentialAndMigrationIsIdempotent() {
        ProgramProgress p = service.get("student");
        assertEquals(Set.of("add"), p.grandfatheredAccess);
        assertSame(p, service.get("student"));
        assertFalse(service.unlocked("student","sub"));
        assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> service.requireLearning("student","add",2));
        assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> service.requireAssessment("student","add"));
        verify(mongo,times(1)).insert(any(ProgramProgress.class));
        verify(users,never()).save(any());
    }
    @Test void activeRunValidationUsesOneProgramReadWithoutReconciliation() {
        service.get("student");
        clearInvocations(mongo);
        service.requireRunAccess("student", "add", 1, "white", false);
        verify(mongo, times(1)).findById("student", ProgramProgress.class);
        verify(mongo, never()).findById("student", User.class);
        verify(mongo, never()).findOne(any(Query.class), eq(AssessmentAttempt.class));
        verify(mongo, never()).save(any(ProgramProgress.class));
    }
    @Test void fastRunChecksStillRejectLockedSubjectsLevelsAndBelts() {
        service.get("student");
        assertThrows(jakarta.ws.rs.ClientErrorException.class,
            () -> service.requireRunAccess("student", "sub", 1, "white", true));
        assertThrows(jakarta.ws.rs.ClientErrorException.class,
            () -> service.requireRunAccess("student", "add", 2, "white", true));
        assertThrows(jakarta.ws.rs.ClientErrorException.class,
            () -> service.requireRunAccess("student", "add", 1, "black-7", false));
        assertDoesNotThrow(() -> service.requireRunAccess("student", "add", 1, "white", true));
        assertDoesNotThrow(() -> service.requireRunAccess("student", "frac", 1, "white", false));
    }
    @Test void completedAssessmentRecoversTransitionAndRetryDoesNotAdvanceAgain() {
        service.get("student"); complete("add",1); complete("add",2);
        assertEquals(Status.ASSESSMENT_READY, service.requireAssessment("student","add").status());
        assessment.set(result(false));
        ProgramProgress p = service.refresh("student");
        assertEquals(2, p.subjects.get("add").batch());
        assertEquals(3, p.subjects.get("add").currentLevel());
        assertEquals(1, service.contentLevel("student","add",3));
        assertEquals(2, service.refresh("student").subjects.get("add").batch());
        assertEquals(Set.of(1,2), p.subjects.get("add").completedLevels());
        assertFalse(service.unlocked("student","sub"));
    }
    @Test void passUnlocksNextSubjectAndHistoricalAssessmentCannotTriggerIt() {
        service.get("student"); complete("add",1); complete("add",2);
        AssessmentAttempt legacy = result(true); legacy.programSubject = null; legacy.programBatch = null;
        assessment.set(legacy);
        assertEquals("add", service.refresh("student").activeSubject);
        assessment.set(result(true));
        assertEquals("sub", service.refresh("student").activeSubject);
        assertEquals(1, service.refresh("student").subjects.get("sub").currentLevel());
        assertTrue(service.unlocked("student","sub"));
        assertDoesNotThrow(() -> service.requireBelt("student", "sub", 1, "white"));
        assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> service.requireBelt("student", "sub", 1, "yellow"));
        Map<?,?> projected = (Map<?,?>)((Map<?,?>)service.project("student",user.getProgress()).get("sub")).get("L1");
        assertEquals(true, ((Map<?,?>)projected.get("white")).get("unlocked"));
    }
    @Test void migrationPreservesLegacyAccessAndSourceProgress() {
        user.setIntegratedEnrollment(null);
        complete("mul",1);
        Map<String,Object> original = new LinkedHashMap<>(user.getProgress());
        var p = service.get("student");
        assertEquals("mul",p.activeSubject);
        assertTrue(p.grandfatheredAccess.contains("mul"));
        assertEquals(original,user.getProgress());
        assertTrue(p.subjects.get("mul").results().isEmpty());
    }
    @Test void storedSnapshotSurvivesConfigurationChange() {
        service.get("student");
        when(config.getMaxLevel("add")).thenReturn(20);
        assertEquals(2,service.batchSize("student","add"));
        assertEquals(1,service.contentLevel("student","add",3));
    }
    @Test void optimisticConflictReloadsAndAppliesResultOnlyOnce() {
        service.get("student"); complete("add",1); complete("add",2);
        assessment.set(result(false));
        when(mongo.findById("student", ProgramProgress.class)).thenAnswer(x -> {
            ProgramProgress saved = stored.get(), copy = new ProgramProgress();
            copy.id = saved.id; copy.enrollment = saved.enrollment; copy.version = saved.version;
            copy.activeSubject = saved.activeSubject; copy.subjects.putAll(saved.subjects);
            copy.grandfatheredAccess.addAll(saved.grandfatheredAccess); return copy;
        });
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        when(mongo.save(any(ProgramProgress.class))).thenAnswer(x -> {
            if (saves.getAndIncrement() == 0) throw new org.springframework.dao.OptimisticLockingFailureException("Concurrent writer");
            stored.set(x.getArgument(0)); return stored.get();
        });
        assertEquals(2, service.refresh("student").subjects.get("add").batch());
        assertEquals(1, service.refresh("student").subjects.get("add").results().size());
        assertEquals(2, saves.get());
    }
    @Test void previousEnrollmentCannotAdvanceResetProgress() {
        service.get("student"); complete("add",1); complete("add",2);
        AssessmentAttempt old = result(true); old.programEnrollment = "previous-enrollment";
        assessment.set(old);
        assertEquals(Status.ASSESSMENT_READY,service.refresh("student").subjects.get("add").status());
    }
    @Test void legacyUnlockedGapsStayAccessibleButDoNotSkipAssessmentEligibility() {
        user.setIntegratedEnrollment(false);
        @SuppressWarnings("unchecked") var levels = (Map<String,Object>)user.getProgress().get("add");
        levels.put("L2",ProgressDefaults.createLevelNode(2,true));
        service.get("student");
        assertDoesNotThrow(() -> service.requireLearning("student","add",2));
        assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> service.requireAssessment("student","add"));
    }
    @Test void missingCurriculumFailsClosedAndOtherOperationsAreUntouched() {
        when(mongo.find(any(Query.class),eq(Catalog.class))).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.curriculum("add"));
        assertDoesNotThrow(() -> service.requireLearning("student","frac",6));
        assertEquals(6,service.contentLevel("student","frac",6));
        verify(mongo,never()).insert(any(ProgramProgress.class));
    }
}
