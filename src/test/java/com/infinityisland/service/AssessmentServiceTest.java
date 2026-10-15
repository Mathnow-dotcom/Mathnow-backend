package com.infinityisland.service;

import com.infinityisland.dao.AssessmentAttempt;
import com.infinityisland.dao.AssessmentAttempt.Item;
import com.infinityisland.dao.Catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentServiceTest {
    @Test void answerTimestampIsRecordedOnceAndLegacyAnswersStillDeserialize() throws Exception {
        var a = attempt();
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "3", 3000);
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "3", 9000);
        assertEquals(new Date(3000), a.answers.get(0).answeredAt());
        assertEquals(1, a.answers.size());
        var legacy = new ObjectMapper().readValue(
            "{\"problem\":\"1 + 2\",\"answer\":\"3\",\"correct\":true,\"timeMs\":1000}", AssessmentAttempt.Answer.class);
        assertNull(legacy.answeredAt());
        var converter = new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
            org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,
            new org.springframework.data.mongodb.core.mapping.MongoMappingContext());
        converter.afterPropertiesSet();
        var oldDocument = new org.bson.Document("problem", "1 + 2").append("answer", "3").append("correct", true).append("timeMs", 1000L);
        assertNull(converter.read(AssessmentAttempt.Answer.class, oldDocument).answeredAt());
        oldDocument.append("answeredAt", new Date(3000));
        assertEquals(new Date(3000), converter.read(AssessmentAttempt.Answer.class, oldDocument).answeredAt());
    }
    @Test void subtractionPoolIncludesEveryTaughtFactWithoutMirrorsOrDuplicates() throws Exception {
        var field = SubtractionCatalogSeeder.class.getDeclaredField("SUB_FACTS");
        field.setAccessible(true);
        int[][][] levels = (int[][][]) field.get(null);
        List<Catalog> catalogs = new ArrayList<>();
        Set<Item> expected = new HashSet<>();
        for (var level : levels) for (var pair : level) {
            catalogs.add(catalog("sub", pair[0], pair[1]));
            expected.add(new Item(pair[0], pair[1]));
        }
        catalogs.add(catalog("add", 99, 1));
        var pool = AssessmentService.buildPool(catalogs, "B");
        assertEquals(11, levels.length);
        assertEquals(66, pool.size());
        assertTrue(pool.contains(new Item(3, 2)));
        assertEquals(expected, new HashSet<>(pool));
        assertEquals(expected.size(), pool.size());
        assertFalse(pool.contains(new Item(1, 5)));
        catalogs.add(catalog("sub", 20, 1));
        assertEquals(pool.size() + 1, AssessmentService.buildPool(catalogs, "B").size());
    }
    @Test void subtractionUsesSameScoringRetryAndStudentPreviewFlow() {
        var a = attempt(); a.type = "B";
        a.items = List.of(new Item(5, 2), new Item(3, 3));
        assertEquals("5 - 2", AssessmentService.student(a).problem());
        assertEquals("3 - 3", AssessmentService.student(a).nextProblem());
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "3", 2000);
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "3", 2500);
        assertEquals(1, a.correctCount); assertEquals(1, a.position);
        assertEquals("5 - 2", a.answers.get(0).problem());
        AssessmentService.applyUpdate(a, "tab-one", 1, "answer", "0", 3000);
        assertTrue(a.completed); assertEquals(2, a.correctCount);
    }
    @Test void onlySupportedTestTypesAreAccepted() {
        assertEquals("A", AssessmentService.testType(null));
        assertEquals("B", AssessmentService.testType("B"));
        assertEquals("C", AssessmentService.testType("C"));
        assertThrows(jakarta.ws.rs.BadRequestException.class, () -> AssessmentService.testType("D"));
    }
    @Test void multiplicationPoolCoversCurriculumWithMirrorsAndNoDuplicates() throws Exception {
        var field = MultiplicationCatalogSeeder.class.getDeclaredField("MUL_FACTS");
        field.setAccessible(true);
        List<Catalog> catalogs = new ArrayList<>();
        Set<Item> expected = new HashSet<>();
        for (var level : (int[][][]) field.get(null)) for (var pair : level) {
            catalogs.add(catalog("mul", pair[0], pair[1]));
            expected.add(new Item(pair[0], pair[1]));
            expected.add(new Item(pair[1], pair[0]));
        }
        catalogs.add(catalog("add", 99, 1));
        catalogs.add(catalog("sub", 98, 1));
        var pool = AssessmentService.buildPool(catalogs, "C");
        assertEquals(100, pool.size());
        assertEquals(expected, new HashSet<>(pool));
        catalogs.add(catalog("mul", 10, 2));
        assertEquals(102, AssessmentService.buildPool(catalogs, "C").size());
    }
    @Test void multiplicationUsesScoringRetryTimestampAndCompletionFlow() {
        var a = attempt(); a.type = "C";
        a.items = List.of(new Item(5, 7), new Item(0, 9));
        assertEquals("5 × 7", AssessmentService.student(a).problem());
        assertEquals("0 × 9", AssessmentService.student(a).nextProblem());
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "35", 2000);
        AssessmentService.applyUpdate(a, "tab-one", 0, "answer", "35", 2500);
        assertEquals(1, a.position);
        assertEquals(new Date(2000), a.answers.get(0).answeredAt());
        assertEquals("5 × 7", a.answers.get(0).problem());
        AssessmentService.applyUpdate(a, "tab-one", 1, "answer", "0", 3000);
        assertTrue(a.completed); assertEquals(2, a.correctCount);
    }
    private Catalog catalog(String op, int a, int b) {
        Catalog c = new Catalog(); c.setOperation(op);
        Catalog.Fact f = new Catalog.Fact(); f.setA(a); f.setB(b);
        c.setFacts(List.of(f)); return c;
    }
    private AssessmentAttempt attempt() {
        AssessmentAttempt a = new AssessmentAttempt();
        a.session = "tab-one"; a.lastTick = new Date(1000);
        a.items = List.of(new Item(1, 2), new Item(2, 1));
        return a;
    }
    @Test void expandsMirrorsAndDeduplicatesAcrossAllBelts() {
        var pool = AssessmentService.buildPool(List.of(catalog("add",1,2), catalog("add",2,1),
            catalog("add",1,1), catalog("add",1,1), catalog("sub",3,1)));
        assertEquals(Set.of(new Item(1,2),new Item(2,1),new Item(1,1)), new HashSet<>(pool));
        assertEquals(3, pool.size());
    }
    @Test void includesEveryCurrentCurriculumItemExactlyOnce() throws Exception {
        // Read the canonical seeder fixture, rather than duplicating the 19-level curriculum here.
        var field = AdditionCatalogSeeder.class.getDeclaredField("ADD_FACTS");
        field.setAccessible(true);
        int[][][] levels = (int[][][]) field.get(null);
        List<Catalog> catalogs = new ArrayList<>();
        for (var level : levels) for (var pair : level) catalogs.add(catalog("add", pair[0], pair[1]));
        var pool = AssessmentService.buildPool(catalogs);
        assertEquals(19, levels.length);
        assertEquals(213, pool.size());
        assertEquals(pool.size(), new HashSet<>(pool).size());
        for (var level : levels) for (var pair : level) {
            assertTrue(pool.contains(new Item(pair[0], pair[1])));
            assertTrue(pool.contains(new Item(pair[1], pair[0])));
        }
        catalogs.add(catalog("add", 30, 1));
        assertEquals(215, AssessmentService.buildPool(catalogs).size());
    }
    @Test void retriesCannotAnswerTheNextQuestionOrDoubleCountTime() {
        var a = attempt();
        AssessmentService.applyUpdate(a,"tab-one",0,"answer","3",3000);
        AssessmentService.applyUpdate(a,"tab-one",0,"answer","3",8000);
        assertEquals(1,a.position); assertEquals(1,a.correctCount);
        assertEquals(2000,a.totalMs); assertEquals(1,a.answers.size());
        assertEquals(0,a.questionMs);
    }
    @Test void incorrectAnswersAdvanceWithoutLearningOrHintsAndCompletionIsIdempotent() {
        var a = attempt();
        AssessmentService.applyUpdate(a,"tab-one",0,"answer","99",3000);
        AssessmentService.applyUpdate(a,"tab-one",1,"answer","003",6000);
        assertTrue(a.completed); assertEquals(1,a.correctCount);
        assertEquals(5000,a.totalMs); assertEquals(3000,a.answers.get(1).timeMs());
        AssessmentService.applyUpdate(a,"tab-one",1,"answer","3",9000);
        assertEquals(5000,a.totalMs); assertEquals(2,a.answers.size());
    }
    @Test void pauseAndResumePreserveQuestionTimeWithoutCountingOfflineGap() {
        var a = attempt();
        AssessmentService.applyUpdate(a,"tab-one",0,"pause",null,4000);
        assertNull(a.lastTick);
        AssessmentService.applyUpdate(a,"tab-one",0,"heartbeat",null,900000);
        AssessmentService.applyUpdate(a,"tab-one",0,"answer","3",902000);
        assertEquals(5000,a.totalMs);
    }
    @Test void staleOrBackwardTimestampsDoNotAddTime() {
        var a = attempt();
        AssessmentService.tick(a,2000);
        AssessmentService.tick(a,50_000_000);
        assertEquals(1000,a.questionMs);
        AssessmentService.tick(a,1000);
        assertEquals(1000,a.questionMs);
    }
    @Test void rejectsOtherTabsAndSkippedQuestions() {
        var a = attempt();
        assertThrows(ClientErrorException.class, () -> AssessmentService.applyUpdate(a,"other-tab",0,"answer","3",3000));
        assertThrows(ClientErrorException.class, () -> AssessmentService.applyUpdate(a,"tab-one",1,"answer","3",3000));
        assertEquals(0,a.position);
    }
    @Test void studentPayloadNeverExposesAnswerKeysOrResults() throws Exception {
        var a = attempt();
        var json = new ObjectMapper().writeValueAsString(AssessmentService.student(a));
        assertTrue(json.contains("1 + 2"));
        assertEquals("2 + 1", AssessmentService.student(a).nextProblem());
        a.position = 1;
        assertNull(AssessmentService.student(a).nextProblem());
        assertFalse(json.contains("correct")); assertFalse(json.contains("items")); assertFalse(json.contains("answers"));
        a.completed = true;
        assertNull(AssessmentService.student(a).problem());
        assertNull(AssessmentService.student(a).nextProblem());
    }
}
