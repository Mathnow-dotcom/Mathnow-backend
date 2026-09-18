package com.infinityisland.service;

import com.infinityisland.model.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuizUtilsFractionTest {

    @Test
    void fractionsUseTheCurriculumThreeDecimalRepresentation() {
        assertEquals(500, QuizUtils.computeAnswer(Operation.FRAC.value(), 1, 2));
        assertEquals(167, QuizUtils.computeAnswer(Operation.FRAC.value(), 1, 6));
        assertEquals("0.5", QuizUtils.formatFractionAnswer(500));
        assertEquals("0.33", QuizUtils.formatFractionAnswer(333));
        assertEquals("0.167", QuizUtils.formatFractionAnswer(167));
        assertEquals("1/6", QuizUtils.buildQuestionText(Operation.FRAC.value(), 1, 6));
    }

    @Test
    void fractionChoicesContainTheCorrectScaledAnswerAndRenderLabels() {
        int correct = QuizUtils.computeAnswer(Operation.FRAC.value(), 7, 8);
        List<Integer> choices = QuizUtils.buildChoices(Operation.FRAC.value(), 7, 8, correct);

        assertEquals(4, choices.size());
        assertTrue(choices.contains(correct));
        assertEquals("0.875", QuizUtils.fractionAnswerLabels(choices).get(correct));
    }
}
