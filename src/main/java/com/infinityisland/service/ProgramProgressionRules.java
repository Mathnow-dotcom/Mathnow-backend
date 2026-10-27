package com.infinityisland.service;

import java.util.*;

/**
 * Side-effect-free rules for integrated learning. ProgramService persists these
 * transitions with optimistic locking and recovers from completed assessments.
 */
public final class ProgramProgressionRules {
    public static final int STANDARD_LEVEL_LIMIT = 100;
    public static final List<String> SUBJECTS = List.of("add", "sub", "mul", "div");
    public enum Status { LEARNING, ASSESSMENT_READY, PASSED, INTERVENTION_REQUIRED }

    /** Snapshot at subject entry; never recompute it for an existing subject. */
    public record Curriculum(List<Integer> baseLevels, Integer additionPart2Start,
                             Integer legacyThrough, Integer legacyBatchSize) {
        public Curriculum(List<Integer> baseLevels) { this(baseLevels, null, null, null); }
        public Curriculum {
            baseLevels = List.copyOf(baseLevels);
            if (additionPart2Start != null && (baseLevels.size() <= 10 || additionPart2Start < 0))
                throw new IllegalArgumentException("Invalid split Addition curriculum");
            if ((legacyThrough == null) != (legacyBatchSize == null) ||
                (legacyBatchSize != null && (legacyBatchSize < 1 || legacyThrough < 0)))
                throw new IllegalArgumentException("Invalid legacy mapping");
            if (baseLevels.isEmpty() || baseLevels.size() > STANDARD_LEVEL_LIMIT)
                throw new IllegalArgumentException("Curriculum must have 1–100 levels");
            for (int i = 0; i < baseLevels.size(); i++)
                if (baseLevels.get(i) != i + 1)
                    throw new IllegalArgumentException("Curriculum levels must be contiguous from 1");
        }
        public boolean splitAddition() { return additionPart2Start != null; }
        public int part(int batch) { return !splitAddition() ? 0 : batch <= 10 ? 1 : 2; }
        public int partBatch(int batch) { return part(batch) == 2 ? batch - 10 : batch; }
        public List<Integer> assessmentLevels(int batch) {
            return !splitAddition() ? baseLevels : part(batch) == 1 ? baseLevels.subList(0, 10) : baseLevels.subList(10, baseLevels.size());
        }
        public int batchSize(int batch) { return assessmentLevels(batch).size(); }
        public int batchSize() { return baseLevels.size(); }
        public int maxBatches() { return splitAddition() ? 10 + STANDARD_LEVEL_LIMIT / (baseLevels.size() - 10) : STANDARD_LEVEL_LIMIT / batchSize(); }
        public int lastLevel() { return splitAddition() ? additionPart2Start == 0 ? 100 : additionPart2Start - 1 + (maxBatches() - 10) * (baseLevels.size() - 10) : maxBatches() * batchSize(); }
        public int baseLevel(int cumulativeLevel) {
            if (cumulativeLevel < 1 || cumulativeLevel > lastLevel())
                throw new IllegalArgumentException("Outside full standard batches");
            if (legacyThrough != null && cumulativeLevel <= legacyThrough)
                return ((cumulativeLevel - 1) % legacyBatchSize) + 1;
            if (splitAddition()) {
                if (additionPart2Start > 0 && cumulativeLevel >= additionPart2Start)
                    return baseLevels.get(10 + (cumulativeLevel - additionPart2Start) % (baseLevels.size() - 10));
                return baseLevels.get((cumulativeLevel - 1) % 10);
            }
            return baseLevels.get((cumulativeLevel - 1) % batchSize());
        }
        public int firstLevel(int batch) {
            requireBatch(batch);
            if (splitAddition()) return part(batch) == 1 ? (batch - 1) * 10 + 1 : additionPart2Start + (batch - 11) * batchSize(batch);
            return (batch - 1) * batchSize() + 1;
        }
        public int lastLevel(int batch) {
            requireBatch(batch);
            return firstLevel(batch) + batchSize(batch) - 1;
        }
        private void requireBatch(int batch) {
            if (batch < 1 || batch > maxBatches()) throw new IllegalArgumentException("Invalid batch");
            if (splitAddition() && batch > 10 && additionPart2Start <= 0) throw new IllegalArgumentException("Addition Part 2 is locked");
        }
    }

    public record ItemOutcome(boolean correct, long timeMs) {
        public ItemOutcome {
            if (timeMs < 0) throw new IllegalArgumentException("Negative response time");
        }
    }
    public record Result(String attemptId, int batch, int count, int correct, long totalMs, boolean passed) {}
    public record State(String subject, Curriculum curriculum, int batch, Status status,
                        Set<Integer> completedLevels, Map<String, Result> results) {
        public State {
            if (!SUBJECTS.contains(subject)) throw new IllegalArgumentException("Unsupported subject");
            curriculum.requireBatch(batch);
            Objects.requireNonNull(status);
            completedLevels = Set.copyOf(completedLevels);
            results = Map.copyOf(results);
            for (int level : completedLevels) curriculum.baseLevel(level);
        }
        public int currentLevel() {
            for (int level = curriculum.firstLevel(batch); level <= curriculum.lastLevel(batch); level++)
                if (!completedLevels.contains(level)) return level;
            return curriculum.lastLevel(batch);
        }
    }

    public static State beginSubject(String subject, Curriculum curriculum) {
        return new State(subject, curriculum, 1, Status.LEARNING, Set.of(), Map.of());
    }

    /** Existing completed levels are imported without modifying the legacy progress document. */
    public static State importCurrentSubject(String subject, Curriculum curriculum, Set<Integer> completed) {
        if (completed.stream().anyMatch(level -> level < 1 || level > curriculum.batchSize(1)))
            throw new IllegalArgumentException("Legacy levels need explicit migration review");
        boolean ready = completed.size() == curriculum.batchSize(1);
        return new State(subject, curriculum, 1, ready ? Status.ASSESSMENT_READY : Status.LEARNING,
            completed, Map.of());
    }

    public static State completeLevel(State state, int cumulativeLevel) {
        state.curriculum().baseLevel(cumulativeLevel);
        // A retried completion must not advance the new batch.
        if (state.completedLevels().contains(cumulativeLevel)) return state;
        if (state.status() != Status.LEARNING || cumulativeLevel != state.currentLevel())
            throw new IllegalStateException("Level is not eligible");
        Set<Integer> completed = new HashSet<>(state.completedLevels());
        completed.add(cumulativeLevel);
        boolean ready = true;
        for (int level = state.curriculum().firstLevel(state.batch());
             level <= state.curriculum().lastLevel(state.batch()); level++)
            ready &= completed.contains(level);
        return new State(state.subject(), state.curriculum(), state.batch(),
            ready ? Status.ASSESSMENT_READY : Status.LEARNING, completed, state.results());
    }

    /**
     * Only final, fully graded assessments may transition progress.
     * Interruptions must not call this method. Retry returns the originally stored result.
     */
    public static State finishAssessment(State state, String attemptId, int batch,
                                         int expectedCount, List<ItemOutcome> answers) {
        if (attemptId == null || attemptId.isBlank()) throw new IllegalArgumentException("Attempt ID required");
        Result original = state.results().get(attemptId);
        if (original != null) {
            if (original.batch() != batch) throw new IllegalStateException("Attempt belongs to another batch");
            return state;
        }
        if (state.status() != Status.ASSESSMENT_READY || state.batch() != batch)
            throw new IllegalStateException("Assessment is not eligible");
        if (expectedCount <= 0 || answers.size() != expectedCount)
            throw new IllegalArgumentException("Assessment is incomplete");
        int correct = 0;
        long total = 0;
        for (ItemOutcome answer : answers) {
            if (answer.correct()) correct++;
            total = Math.addExact(total, answer.timeMs());
        }
        // Integer comparison is exact: no rounding or floating-point division.
        boolean passed = correct == expectedCount && total < 3000L * expectedCount;
        Result result = new Result(attemptId, batch, expectedCount, correct, total, passed);
        Map<String, Result> results = new HashMap<>(state.results());
        results.put(attemptId, result);
        if (passed && state.curriculum().splitAddition() && state.curriculum().part(batch) == 1) {
            Curriculum previous = state.curriculum();
            Curriculum part2 = new Curriculum(previous.baseLevels(), previous.lastLevel(batch) + 1,
                previous.legacyThrough(), previous.legacyBatchSize());
            return new State(state.subject(), part2, 11, Status.LEARNING, state.completedLevels(), results);
        }
        boolean finalBatch = state.curriculum().splitAddition() && state.curriculum().part(batch) == 1
            ? batch == 10 : batch == state.curriculum().maxBatches();
        Status next = passed ? Status.PASSED : finalBatch
            ? Status.INTERVENTION_REQUIRED : Status.LEARNING;
        return new State(state.subject(), state.curriculum(),
            next == Status.LEARNING ? batch + 1 : batch, next, state.completedLevels(), results);
    }

    public static Optional<String> nextSubject(State state) {
        if (state.status() != Status.PASSED) throw new IllegalStateException("Subject has not passed");
        int next = SUBJECTS.indexOf(state.subject()) + 1;
        return next == SUBJECTS.size() ? Optional.empty() : Optional.of(SUBJECTS.get(next));
    }

    private ProgramProgressionRules() {}
}
