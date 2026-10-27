package com.infinityisland.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static com.infinityisland.service.ProgramProgressionRules.*;

class ProgramProgressionRulesTest {
    Curriculum curriculum(int size) {
        return new Curriculum(IntStream.rangeClosed(1, size).boxed().toList());
    }
    State ready(State state) {
        while (state.status() == Status.LEARNING) state = completeLevel(state, state.currentLevel());
        return state;
    }
    @Test void limitsAndCumulativeMappingMatchPrdAndFutureCurricula() {
        int[][] examples = {{19,5,95}, {11,9,99}, {10,10,100}, {13,7,91}, {20,5,100}};
        for (var example : examples) {
            var c = curriculum(example[0]);
            assertEquals(example[1], c.maxBatches());
            assertEquals(example[2], c.lastLevel());
            for (int level = 1; level <= c.lastLevel(); level++)
                assertEquals((level - 1) % example[0] + 1, c.baseLevel(level));
            assertThrows(IllegalArgumentException.class, () -> c.baseLevel(c.lastLevel() + 1));
        }
        assertEquals(1, curriculum(19).baseLevel(20));
        assertEquals(1, curriculum(19).baseLevel(39));
    }
    @Test void snapshotCannotChangeWhenCurriculumChanges() {
        var levels = new ArrayList<>(IntStream.rangeClosed(1, 19).boxed().toList());
        var snapshot = new Curriculum(levels);
        levels.add(20);
        assertEquals(19, snapshot.batchSize());
        assertEquals(20, new Curriculum(levels).batchSize());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.baseLevels().add(20));
        assertThrows(IllegalArgumentException.class, () -> new Curriculum(List.of(1,3)));
    }
    @Test void exactSpeedAndAccuracyBoundaries() {
        for (long time : new long[]{2999, 3000, 3001}) {
            var s = ready(beginSubject("add", curriculum(19)));
            var result = finishAssessment(s, "a", 1, 1, List.of(new ItemOutcome(true, time)));
            assertEquals(time < 3000, result.results().get("a").passed());
        }
        var s = ready(beginSubject("add", curriculum(19)));
        assertFalse(finishAssessment(s, "wrong", 1, 2,
            List.of(new ItemOutcome(true, 1000), new ItemOutcome(false, 1000))).results().get("wrong").passed());
        assertTrue(finishAssessment(s, "fractional", 1, 2,
            List.of(new ItemOutcome(true, 2999), new ItemOutcome(true, 3000))).results().get("fractional").passed());
    }
    @Test void noSkippedLevelsAndNoTransitionForInterruptedAssessment() {
        var s = beginSubject("add", curriculum(19));
        assertThrows(IllegalStateException.class, () -> completeLevel(s, 2));
        assertThrows(IllegalStateException.class, () -> finishAssessment(s, "early", 1, 1, List.of(new ItemOutcome(true, 1))));
        var r = ready(s);
        assertThrows(IllegalArgumentException.class, () -> finishAssessment(r, "partial", 1, 2, List.of(new ItemOutcome(true, 1))));
        assertEquals(Status.ASSESSMENT_READY, r.status());
        assertTrue(r.results().isEmpty());
    }
    @Test void failedBatchesPreserveEarlierLevelsAndStopAtFinalIntervention() {
        var s = beginSubject("div", curriculum(13));
        for (int batch = 1; batch <= 7; batch++) {
            s = ready(s);
            s = finishAssessment(s, "attempt-" + batch, batch, 1, List.of(new ItemOutcome(false, 1000)));
            assertEquals(batch * 13, s.completedLevels().size());
            assertEquals(batch, s.results().size());
            assertEquals(batch == 7 ? Status.INTERVENTION_REQUIRED : Status.LEARNING, s.status());
        }
        var terminal = s;
        assertThrows(IllegalStateException.class, () -> finishAssessment(terminal, "retake", 7, 1, List.of(new ItemOutcome(true, 1))));
        assertSame(s, finishAssessment(s, "attempt-7", 7, 1, List.of(new ItemOutcome(true, 1))));
    }
    @Test void duplicatesAndSequentialSubjectUnlocking() {
        for (int i = 0; i < SUBJECTS.size(); i++) {
            var s = ready(beginSubject(SUBJECTS.get(i), curriculum(2)));
            assertSame(s, completeLevel(s, 2));
            var passed = finishAssessment(s, "pass", 1, 1, List.of(new ItemOutcome(true, 1000)));
            assertSame(passed, finishAssessment(passed, "pass", 1, 1, List.of(new ItemOutcome(false, 9999))));
            assertEquals(i == 3 ? Optional.empty() : Optional.of(SUBJECTS.get(i+1)), nextSubject(passed));
        }
    }
    @Test void legacyImportPreservesCompletedLevelsAndFillsGapsSequentially() {
        var completed = new HashSet<>(Set.of(1,3));
        var s = importCurrentSubject("mul", curriculum(10), completed);
        assertEquals(Set.of(1,3), s.completedLevels());
        assertEquals(2, s.currentLevel());
        assertEquals(4, completeLevel(s, 2).currentLevel());
        assertEquals(Set.of(1,3), completed);
        assertTrue(s.results().isEmpty());
    }
}
