package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.model.Belt;
import com.infinityisland.repositories.CatalogRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual existing builders, not substitute generators. No database connections. */
class ProgramGeneratorTest {
    @Test void laterBatchUsesBaseContentInEveryBuilderWithoutChangingRunIdentity() throws Exception {
        var config = mock(GameConfigService.class);
        var runs = mock(CachedQuizRunService.class);
        var questions = mock(CachedQuestionService.class);
        var attempts = mock(AttemptService.class);
        var daily = mock(DailyService.class);
        var progression = mock(ProgressionService.class);
        var program = mock(ProgramService.class);
        var catalog = mock(CatalogRepository.class);
        var helper = new QuizHelper(runs,questions,config,daily,progression,attempts);
        var field = QuizHelper.class.getDeclaredField("program"); field.setAccessible(true); field.set(helper,program);
        field = QuizHelper.class.getDeclaredField("catalogRepo"); field.setAccessible(true); field.set(helper,catalog);
        when(program.contentLevel("student","add",20)).thenReturn(1);
        when(questions.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));
        when(config.getPretestQuestionCount()).thenReturn(20);
        when(config.getRocketQuestionsPerQuiz()).thenReturn(6);
        when(config.getSurfQuestionsPerQuiz()).thenReturn(6);
        when(config.getBonusQuestionsPerBatch()).thenReturn(10);
        List<Catalog> facts = new ArrayList<>();
        for (String belt : Belt.COLORED_ORDER) {
            Catalog c = new Catalog(); c.setOperation("add"); c.setLevel("1"); c.setBelt(belt);
            Catalog.Fact f = new Catalog.Fact(); f.setA(facts.size()); f.setB(0); c.setFacts(List.of(f)); facts.add(c);
        }
        when(catalog.findAll()).thenReturn(facts);
        helper.warmCanonicalPairCache();
        QuizRun run = new QuizRun(); run.setUserId("student"); run.setOperation("add"); run.setLevel(20); run.setBeltOrDegree("white");
        var normal = new NormalModeHandler(helper,runs,questions,attempts,daily,config,progression);
        try {
            List<List<GeneratedQuestion>> sets = new ArrayList<>();
            sets.add(normal.buildQuizSet(run));
            assertEquals(10,sets.get(0).size()); // Includes the existing L1 digit-recognition special case.
            sets.add(new PretestModeHandler(helper,runs,questions,attempts,daily,config,progression).buildPretestQuizSet(run));
            sets.add(new SurfModeHandler(helper,runs,questions,attempts,daily,config).buildSurfQuizSet(run));
            sets.add(new RocketModeHandler(helper,runs,questions,attempts,daily,config,progression).buildRocketQuizSet(run));
            sets.add(new BonusModeHandler(helper,runs,questions,attempts,daily,config,progression).buildBonusQuizSet(run));
            run.setBeltOrDegree("black-7"); sets.add(normal.buildQuizSet(run));
            for (var set : sets) {
                assertFalse(set.isEmpty());
                assertTrue(set.stream().allMatch(q -> q.getLevel() == 1));
            }
            assertEquals(20,run.getLevel());
            run.setBeltOrDegree("white");
            var prepared = normal.prepareNormalMode("student",20,"white","add");
            assertFalse(prepared.practice.isEmpty());
            assertTrue(prepared.practice.stream().allMatch(q -> ((GeneratedQuestion)q).getLevel() == 1));
            verify(runs).save(argThat(saved -> saved.getLevel() == 20));
        } finally { helper.shutdown(); }
    }
}
