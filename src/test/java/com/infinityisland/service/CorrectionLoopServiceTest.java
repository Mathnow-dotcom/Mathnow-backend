package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.controller.QuizResponses.AnswerResponse;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrectionLoopServiceTest {
    final MongoTemplate mongo=mock(MongoTemplate.class);
    final CachedQuizRunService runs=mock(CachedQuizRunService.class);
    final GameConfigService config=mock(GameConfigService.class);
    final QuizHelper helper=new QuizHelper(runs,mock(CachedQuestionService.class),config,
        mock(DailyService.class),mock(ProgressionService.class),mock(AttemptService.class));
    final CorrectionLoopService service=new CorrectionLoopService(mongo,helper,runs,config);
    @BeforeEach void setup() {
        when(config.getCorrectionRoundLimitMs()).thenReturn(6000L);
        history(List.of());
    }
    @AfterEach void cleanup() { helper.shutdown(); }
    void history(List<Document> facts) {
        when(mongo.aggregate(any(Aggregation.class),eq("attempts"),eq(Document.class)))
            .thenReturn(new AggregationResults<>(facts,new Document()));
    }
    QuizRun run(String op,int a,int b) {
        QuizRun run=new QuizRun(); run.setId("run"); run.setUserId("student");run.setOperation(op);run.setLevel(2);
        GeneratedQuestion q=helper.buildQuestionObject(op,2,"yellow",a,b,"current",null); q.setId("missed");
        AnswerResponse response=new AnswerResponse();response.practice=q;
        assertSame(response,service.intercept(run,response));
        return run;
    }
    @ParameterizedTest @ValueSource(strings={"sub","mul","div","frac"})
    void fallbackUsesAdditionForEverySubject(String op) {
        QuizRun run=run(op,4,2);
        assertEquals(3,run.correctionLoop.facts.size());
        assertEquals(op,run.correctionLoop.facts.get(0).getOperation());
        assertEquals("1 + 1",run.correctionLoop.facts.get(1).getQuestion());
        assertEquals("2 + 2",run.correctionLoop.facts.get(2).getQuestion());
        verify(runs).saveSync(run);
    }
    @Test void currentAndHistoryAreDeduplicatedBeforeFallback() {
        history(List.of(fact(1,1),fact(4,2)));
        var c=run("add",1,1).correctionLoop;
        assertEquals(List.of("1 + 1","4 + 2","2 + 2"),c.facts.stream().map(GeneratedQuestion::getQuestion).toList());
    }
    @Test void missingHistoryAndCurrentFallbackStillProducesThreeDistinctFacts() {
        var c=run("add",1,1).correctionLoop;
        assertEquals(List.of("1 + 1","2 + 2","3 + 3"),c.facts.stream().map(GeneratedQuestion::getQuestion).toList());
    }
    Document fact(int a,int b) {return new Document("_id",new Document("a",a).append("b",b));}
    void next(QuizRun.CorrectionLoop c) {CorrectionLoopService.apply(c,"next",null,0);assertEquals(Set.of(0,1,2),new HashSet<>(c.order));}
    void correct(QuizRun.CorrectionLoop c,long ms) {CorrectionLoopService.apply(c,"answer",c.facts.get(c.order.get(c.position)).getCorrectAnswer(),ms);}
    @Test void requiresThreeUnassistedAnswersWithinOneTotalLimit() {
        var c=run("sub",4,2).correctionLoop;next(c);
        correct(c,2000);correct(c,2000);assertEquals("practice",c.phase);correct(c,2000);
        assertEquals("passed",c.phase);assertEquals(6000,c.elapsedMs);
    }
    @Test void oneMillisecondOverTotalRestartsEntireRound() {
        var c=run("sub",4,2).correctionLoop;next(c);
        correct(c,2000);correct(c,2000);correct(c,2001);
        assertEquals("answer",c.phase);next(c);assertEquals(0,c.position);assertEquals(0,c.elapsedMs);
    }
    @Test void wrongAndTimeoutShowAnswerAndRequireNext() {
        var c=run("sub",4,2).correctionLoop;next(c);correct(c,500);
        int wrongIndex=c.order.get(1);CorrectionLoopService.apply(c,"answer",-1,500);
        assertEquals("answer",c.phase);assertEquals(wrongIndex,c.shown);
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->correct(c,1));
        next(c);CorrectionLoopService.apply(c,"timeout",null,6001);assertEquals("answer",c.phase);
    }
    @Test void pausePreservesRoundPositionAndOnlyActiveTime() {
        var c=run("sub",4,2).correctionLoop;next(c);correct(c,1000);
        CorrectionLoopService.apply(c,"pause",null,500);
        assertEquals(1,c.position);assertEquals(1500,c.elapsedMs);
        correct(c,1000);correct(c,1000);assertEquals("passed",c.phase);assertEquals(3500,c.elapsedMs);
    }
    @Test void repeatedRequestsDoNotAdvanceOrCountTimeTwice() {
        var run=run("sub",4,2);var c=run.correctionLoop;
        service.act(run,c.id,0,"next",null,0);
        int answer=c.facts.get(c.order.get(0)).getCorrectAnswer();
        service.act(run,c.id,1,"answer",answer,1000);
        service.act(run,c.id,1,"answer",answer,1000);
        assertEquals(1,c.position);assertEquals(1000,c.elapsedMs);assertEquals(2,c.revision);
        assertThrows(jakarta.ws.rs.BadRequestException.class,()->service.act(run,"other",2,"next",null,0));
    }
    @Test void pendingWrongResponseNeverReplacesSavedRound() {
        var run=run("sub",4,2);var loop=run.correctionLoop;
        AnswerResponse out=new AnswerResponse();out.practice=loop.originalPractice;
        service.intercept(run,out);assertSame(loop,run.correctionLoop);verify(runs,times(1)).saveSync(run);
    }
    @Test void savedRoundAndCompletionReceiptSurviveMongoRoundTrip() throws Exception {
        var context=new org.springframework.data.mongodb.core.mapping.MongoMappingContext();
        var conversions=org.springframework.data.mongodb.core.convert.MongoCustomConversions.create(adapter -> {});
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());context.afterPropertiesSet();
        var converter=new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
            org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,context);
        converter.setCustomConversions(conversions);converter.afterPropertiesSet();
        var run=run("frac",1,3);next(run.correctionLoop);correct(run.correctionLoop,750);
        Document stored=new Document();converter.write(run,stored);
        var restored=converter.read(QuizRun.class,stored);
        assertEquals(run.correctionLoop.order,restored.correctionLoop.order);
        assertEquals(1,restored.correctionLoop.position);assertEquals(750,restored.correctionLoop.elapsedMs);
        assertEquals(1000,restored.correctionLoop.facts.get(0).getAnswerScale());
        var receipt=new AnswerResponse();receipt.completed=true;receipt.passed=false;
        restored.correctionLoop.phase="finished";restored.correctionLoop.completionResponse=receipt;
        stored=new Document();converter.write(restored,stored);
        var finalRun=converter.read(QuizRun.class,stored);
        assertTrue(((AnswerResponse)finalRun.correctionLoop.completionResponse).completed);
    }
    @Test void oldConfigDefaultsToSixSecondsAndAdminCanChangeIt() {
        var repo=mock(com.infinityisland.repositories.GameConfigRepository.class);
        var cfg=new GameConfig();cfg.setId("default");
        when(repo.findById("default")).thenReturn(Optional.of(cfg));
        when(repo.save(any(GameConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        var service=new GameConfigService(repo,0);service.init();
        assertEquals(6000,service.getCorrectionRoundLimitMs());
        var update=new GameConfig();update.setCorrectionRoundLimitMs(9000L);service.updateConfig(update,"admin");
        assertEquals(9000,service.getCorrectionRoundLimitMs());
        service.updateConfig(new GameConfig(),"admin");assertEquals(9000,service.getCorrectionRoundLimitMs());
        update.setCorrectionRoundLimitMs(0L);
        assertThrows(IllegalArgumentException.class,()->service.updateConfig(update,"admin"));
    }
    @ParameterizedTest @ValueSource(strings={"add","sub","mul","div","frac"})
    void rocketUsesReverseExpressionChoicesAndKeepsSavedRound(String op) {
        var run=run(op,4,2);var c=run.correctionLoop;
        next(c);correct(c,750);
        var original=c.originalPractice;var order=new ArrayList<>(c.order);
        run.setGameMode(true);run.setGameModeType("rocket");
        service.state(run); // also upgrades loops saved before reverse UI was restored
        assertEquals(1,c.position);assertEquals(750,c.elapsedMs);assertEquals(order,c.order);
        assertSame(original,c.originalPractice);
        long revision=c.revision;
        for(var q:c.facts) {
            assertEquals(4,q.getTextChoices().size());
            String expected=QuizUtils.buildQuestionText(q.getOperation(),q.getParams().getA(),q.getParams().getB());
            assertEquals(expected,q.getTextChoices().get(q.getCorrectAnswer()));
            assertEquals(4,new HashSet<>(q.getTextChoices()).size());
        }
        service.state(run);assertEquals(revision,c.revision,"Do not regenerate choices on read");
        correct(c,750);correct(c,750);assertEquals("passed",c.phase);
    }
    @Test void resumeLookupIsScopedToSubjectLevelAndBeltOrPretest() {
        service.pendingRun("student","sub",13,"black-7");
        var query=org.mockito.ArgumentCaptor.forClass(org.springframework.data.mongodb.core.query.Query.class);
        verify(mongo).findOne(query.capture(),eq(QuizRun.class));
        var doc=query.getValue().getQueryObject();
        assertEquals("student",doc.get("userId"));assertEquals("sub",doc.get("operation"));
        assertEquals(13,doc.get("level"));
        assertEquals(List.of(new Document("pretestMode",true),new Document("beltOrDegree","black-7")),doc.get("$or"));
    }
}
