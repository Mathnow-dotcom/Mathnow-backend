package com.infinityisland.service;

import com.infinityisland.dao.GeneratedQuestion;
import com.infinityisland.dao.QuizRun;
import com.infinityisland.model.Belt;
import com.infinityisland.model.Operation;
import com.infinityisland.model.QuizStatus;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure static utility methods shared across quiz mode handlers.
 */
public final class QuizUtils {

    public static final int COLORED_COUNT = 10;
    public static final int BLACK_COUNT = 20;
    public static final int PRETEST_COUNT = 20;

    private QuizUtils() {}

    // ===== Null-safe helpers =====

    public static int nvl(Integer v, int d) {
        return v == null ? d : v;
    }

    public static long nvl(Long v, long d) {
        return v == null ? d : v;
    }

    public static String nvl(String v, String d) {
        return v == null ? d : v;
    }

    public static int safe(Integer i, int d) {
        return i == null ? d : i;
    }

    // ===== Belt helpers =====

    public static boolean isBlack(String belt) {
        return belt != null && belt.toLowerCase(Locale.ROOT).matches("black-\\d");
    }

    public static int parseBlackDegree(String belt) {
        try {
            if (belt == null) return 1;
            String lower = belt.toLowerCase(Locale.ROOT);
            if (lower.matches("black-\\d")) {
                return Integer.parseInt(lower.substring("black-".length()));
            } else if (lower.startsWith("black-degree-")) {
                return Integer.parseInt(lower.substring("black-degree-".length()));
            }
            return 1;
        } catch (Exception e) {
            return 1;
        }
    }

    public static boolean isCommutative(String op) {
        return Operation.ADD.value().equalsIgnoreCase(op)
                || Operation.MUL.value().equalsIgnoreCase(op)
                || "multiply".equalsIgnoreCase(op);
    }

    // ===== Question text / answer =====

    public static String buildQuestionText(String op, int a, int b) {
        if (Operation.ADD.value().equalsIgnoreCase(op)) return a + " + " + b;
        if (Operation.SUB.value().equalsIgnoreCase(op) || "subtract".equalsIgnoreCase(op)) return a + " - " + b;
        if (Operation.MUL.value().equalsIgnoreCase(op) || "multiply".equalsIgnoreCase(op)) return a + " × " + b;
        if (Operation.DIV.value().equalsIgnoreCase(op) || "divide".equalsIgnoreCase(op)) return a + " ÷ " + b;
        return a + " ? " + b;
    }

    public static int computeAnswer(String op, int a, int b) {
        if (Operation.ADD.value().equalsIgnoreCase(op)) return a + b;
        if (Operation.SUB.value().equalsIgnoreCase(op) || "subtract".equalsIgnoreCase(op)) return a - b;
        if (Operation.MUL.value().equalsIgnoreCase(op) || "multiply".equalsIgnoreCase(op)) return a * b;
        if (Operation.DIV.value().equalsIgnoreCase(op) || "divide".equalsIgnoreCase(op)) {
            if (b == 0) throw new IllegalArgumentException("division by zero (a=" + a + ")");
            return a / b;
        }
        return a + b;
    }

    // ===== Choice builders =====

    public static List<Integer> buildChoices(int correct) {
        return buildChoices(null, 0, 0, correct);
    }

    /**
     * Operation-aware multiple-choice generator.
     *
     * For multiplication the distractors are drawn from nearby factor pairs so the
     * options aren't just arithmetic neighbours (kids learn 6×7=42's neighbours are
     * not products of small single-digit factors). For add/sub/div we keep the
     * existing ±1, ±2 heuristic — curriculum-appropriate at small numbers.
     *
     * Edge case (PRD page 8): when correct == 0, return {0, 1, 2, 3} for any op
     * so the child always sees meaningful options.
     */
    public static List<Integer> buildChoices(String op, int a, int b, int correct) {
        if (correct == 0) {
            List<Integer> fallback = new ArrayList<>(List.of(0, 1, 2, 3));
            Collections.shuffle(fallback);
            return fallback;
        }

        if (Operation.MUL.value().equalsIgnoreCase(op) || "multiply".equalsIgnoreCase(op)) {
            return buildMultiplicationChoices(a, b, correct);
        }

        Set<Integer> s = new LinkedHashSet<>();
        s.add(correct);
        s.add(Math.max(0, correct + 1));
        s.add(Math.max(0, correct - 1));
        s.add(Math.max(0, correct + 2));

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = 0;
        while (s.size() < 4 && attempts < 20) {
            int offset = rnd.nextInt(-2, 5);
            int candidate = correct + offset;
            if (candidate >= 0) {
                s.add(candidate);
            }
            attempts++;
        }

        int nextCandidate = Math.max(0, correct + 3);
        while (s.size() < 4) {
            s.add(nextCandidate);
            nextCandidate++;
        }

        List<Integer> choices = new ArrayList<>(s);
        Collections.shuffle(choices);
        return choices;
    }

    private static List<Integer> buildMultiplicationChoices(int a, int b, int correct) {
        // Candidate distractors: products of nearby factor pairs.
        LinkedHashSet<Integer> pool = new LinkedHashSet<>();
        int[][] offsets = {
                { -1,  0 }, { +1,  0 },          // off-by-one on a
                {  0, -1 }, {  0, +1 },          // off-by-one on b
                { -1, -1 }, { +1, +1 },          // diagonal
                { -1, +1 }, { +1, -1 },          // anti-diagonal
                { -2,  0 }, { +2,  0 },
                {  0, -2 }, {  0, +2 },
        };
        for (int[] off : offsets) {
            int na = a + off[0];
            int nb = b + off[1];
            if (na < 0 || nb < 0 || na > 9 || nb > 9) continue;
            int product = na * nb;
            if (product == correct || product < 0) continue;
            pool.add(product);
        }

        // Trim to 3 distractors. Shuffle for variety.
        List<Integer> distractors = new ArrayList<>(pool);
        Collections.shuffle(distractors);
        while (distractors.size() > 3) {
            distractors.remove(distractors.size() - 1);
        }

        // Fallback if not enough product-based candidates (shouldn't happen for 0<correct<=81).
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int safety = 0;
        while (distractors.size() < 3 && safety < 30) {
            int candidate = correct + rnd.nextInt(-3, 4);
            if (candidate >= 0 && candidate != correct && !distractors.contains(candidate)) {
                distractors.add(candidate);
            }
            safety++;
        }

        List<Integer> choices = new ArrayList<>(distractors);
        choices.add(correct);
        Collections.shuffle(choices);
        return choices;
    }

    public static List<Integer> buildPracticeChoices(int correct) {
        Set<Integer> s = new LinkedHashSet<>();
        s.add(correct);
        s.add(correct + 1);
        s.add(Math.max(0, correct - 1));
        s.add(correct + 2);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int attempts = 0;
        while (s.size() < 4 && attempts < 20) {
            int offset = rnd.nextInt(-2, 5);
            int candidate = correct + offset;
            if (candidate >= 0) {
                s.add(candidate);
            }
            attempts++;
        }

        int nextCandidate = correct + 3;
        while (s.size() < 4) {
            if (nextCandidate >= 0) {
                s.add(nextCandidate);
            }
            nextCandidate++;
        }

        List<Integer> choices = new ArrayList<>(s);
        Collections.shuffle(choices);
        return choices;
    }

    // ===== Question reordering =====

    public static List<GeneratedQuestion> reorderNoConsecutiveDuplicates(List<GeneratedQuestion> questions) {
        if (questions.size() <= 1) return questions;

        Map<String, List<GeneratedQuestion>> groups = new LinkedHashMap<>();
        for (GeneratedQuestion q : questions) {
            groups.computeIfAbsent(q.getQuestion(), k -> new ArrayList<>()).add(q);
        }

        PriorityQueue<List<GeneratedQuestion>> pq = new PriorityQueue<>(
                (a, b) -> b.size() - a.size()
        );
        pq.addAll(groups.values());

        List<GeneratedQuestion> result = new ArrayList<>(questions.size());
        String lastText = null;

        while (!pq.isEmpty()) {
            List<GeneratedQuestion> pick = pq.poll();

            if (pick.get(0).getQuestion().equals(lastText) && !pq.isEmpty()) {
                List<GeneratedQuestion> second = pq.poll();
                pq.offer(pick);
                pick = second;
            }

            GeneratedQuestion q = pick.remove(pick.size() - 1);
            result.add(q);
            lastText = q.getQuestion();

            if (!pick.isEmpty()) {
                pq.offer(pick);
            }
        }

        return result;
    }

    // ===== Guard methods =====

    public static void guardRunning(QuizRun run) {
        if (!QuizStatus.RUNNING.value().equalsIgnoreCase(nvl(run.getStatus(), ""))) {
            throw new IllegalStateException("quiz-not-running");
        }
    }

    public static void guardRunningOrPrepared(QuizRun run) {
        String st = nvl(run.getStatus(), "");
        if (!QuizStatus.RUNNING.value().equalsIgnoreCase(st) && !QuizStatus.PREPARED.value().equalsIgnoreCase(st)) {
            throw new IllegalStateException("quiz-invalid-state");
        }
    }

    // ===== Question index helpers =====

    public static String currentQuestionId(QuizRun run) {
        List<String> items = run.getItems();
        int idx = nvl(run.getCurrentIndex(), 0);

        if ((run.isLightningMode() || run.isSurfMode() || run.isRocketMode()) && items != null && !items.isEmpty()) {
            idx = idx % items.size();
        }

        if (items == null || items.isEmpty() || idx < 0 || idx >= items.size()) return null;
        return items.get(idx);
    }

    public static String previousQuestionId(QuizRun run) {
        List<String> items = run.getItems();
        int idx = nvl(run.getCurrentIndex(), 0) - 1;

        if ((run.isLightningMode() || run.isSurfMode() || run.isRocketMode()) && items != null && !items.isEmpty()) {
            if (idx < 0) idx = items.size() - 1;
            idx = idx % items.size();
        }

        if (items == null || items.isEmpty() || idx < 0 || idx >= items.size()) return null;
        return items.get(idx);
    }

    // ===== Expression choices (shared by surf/rocket) =====

    public static List<String> buildExpressionChoices(String op, int correctA, int correctB,
                                                       int targetAnswer, List<int[]> factPool) {
        String correctExpr = buildQuestionText(op, correctA, correctB);
        Set<String> choices = new LinkedHashSet<>();
        choices.add(correctExpr);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        List<int[]> shuffledPool = new ArrayList<>(factPool);
        Collections.shuffle(shuffledPool);
        for (int[] pair : shuffledPool) {
            if (choices.size() >= 4) break;
            int pa = pair[0], pb = pair[1];
            int result = computeAnswer(op, pa, pb);
            if (result != targetAnswer) {
                choices.add(buildQuestionText(op, pa, pb));
            }
            if (choices.size() < 4 && pa != pb && isCommutative(op)) {
                result = computeAnswer(op, pb, pa);
                if (result != targetAnswer) {
                    choices.add(buildQuestionText(op, pb, pa));
                }
            }
        }

        int attempts = 0;
        while (choices.size() < 4 && attempts < 30) {
            int da = correctA + rnd.nextInt(-2, 3);
            int db = correctB + rnd.nextInt(-2, 3);
            if (da < 0) da = 0;
            if (db < 0) db = 0;
            if (!isCommutative(op) && da < db) { int temp = da; da = db; db = temp; }
            int result = computeAnswer(op, da, db);
            if (result != targetAnswer) {
                choices.add(buildQuestionText(op, da, db));
            }
            attempts++;
        }

        int offset = 1;
        while (choices.size() < 4) {
            int da = correctA + offset;
            int db = correctB;
            if (!isCommutative(op) && da < db) { int temp = da; da = db; db = temp; }
            if (computeAnswer(op, da, db) != targetAnswer) {
                choices.add(buildQuestionText(op, da, db));
            }
            offset++;
        }

        List<String> resultList = new ArrayList<>(choices);
        Collections.shuffle(resultList);
        return resultList;
    }
}
