package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.controller.QuizResponses.AnswerResponse;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import jakarta.ws.rs.BadRequestException;
import java.util.*;

/** Shared, score-free correction state, persisted with the interrupted run. */
@Service
public class CorrectionLoopService {
    private final MongoTemplate mongo;
    private final QuizHelper helper;
    private final CachedQuizRunService runs;
    private final GameConfigService config;
    public CorrectionLoopService(MongoTemplate mongo, QuizHelper helper, CachedQuizRunService runs, GameConfigService config) {
        this.mongo=mongo; this.helper=helper; this.runs=runs; this.config=config;
    }
    public boolean pending(QuizRun run) {
        return run.correctionLoop != null && !"finished".equals(run.correctionLoop.phase);
    }
    public String pendingRun(String userId) {
        QuizRun run = mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
            Criteria.where("userId").is(userId).and("correctionLoop.phase").in("answer","practice","passed")), QuizRun.class);
        return run == null ? null : run.getId();
    }
    public QuizRun pendingRun(String userId, String operation, int level, String belt) {
        return mongo.findOne(org.springframework.data.mongodb.core.query.Query.query(
            Criteria.where("userId").is(userId).and("operation").is(operation).and("level").is(level)
                .and("correctionLoop.phase").in("answer","practice","passed")
                .orOperator(Criteria.where("pretestMode").is(true), Criteria.where("beltOrDegree").is(belt))), QuizRun.class);
    }
    public Object intercept(QuizRun run, Object response) {
        if (response instanceof AnswerResponse out && out.practice instanceof GeneratedQuestion missed) {
            synchronized (run) {
                if (!pending(run)) {
                    var loop = new QuizRun.CorrectionLoop();
                    loop.id = UUID.randomUUID().toString();
                    loop.originalPractice = missed;
                    loop.limitMs = config.getCorrectionRoundLimitMs();
                    LinkedHashMap<String,GeneratedQuestion> pool = new LinkedHashMap<>();
                    add(pool, forward(missed));
                    var aggregation = Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("userId").is(run.getUserId()).and("operation").is(run.getOperation())
                            .and("correct").is(false).and("reason").ne("practice").and("a").ne(null).and("b").ne(null)),
                        Aggregation.group("operation", "a", "b").count().as("misses"),
                        Aggregation.sort(Sort.by(Sort.Order.desc("misses"), Sort.Order.asc("_id.a"), Sort.Order.asc("_id.b"))));
                    for (Document result : mongo.aggregate(aggregation, "attempts", Document.class).getMappedResults()) {
                        if (pool.size() == 3) break;
                        Document fact = result.get("_id", Document.class);
                        add(pool, helper.buildQuestionObject(run.getOperation(), run.getLevel(), "correction",
                            ((Number)fact.get("a")).intValue(), ((Number)fact.get("b")).intValue(), "correction", null));
                    }
                    // Usually 1+1 and 2+2. If either is already selected, use the next distinct double.
                    for (int n=1; pool.size()<3; n++)
                        add(pool, helper.buildQuestionObject("add", 1, "correction", n,n,"correction",null));
                    loop.facts.addAll(pool.values());
                    if (run.isRocketMode()) reverseFacts(loop);
                    run.correctionLoop = loop;
                    helper.pauseTimer(run);
                    runs.saveSync(run);
                }
            }
        }
        return response;
    }
    private void reverseFacts(QuizRun.CorrectionLoop loop) {
        var facts = loop.facts;
        loop.facts = facts.stream().map(q -> helper.buildRocketQuestion(q.getOperation(), q.getLevel(), "correction",
            q.getParams().getA(), q.getParams().getB(), facts.stream()
                .filter(f -> Objects.equals(f.getOperation(), q.getOperation()))
                .map(f -> new int[]{f.getParams().getA(), f.getParams().getB()}).toList())).toList();
    }
    // Upgrade a saved forward Rocket loop without resetting its round or elapsed time.
    private void ensureRocketFormat(QuizRun run) {
        synchronized (run) {
            var c = run.correctionLoop;
            if (run.isRocketMode() && c != null && pending(run) &&
                    c.facts.stream().anyMatch(q -> q.getTextChoices() == null || q.getTextChoices().isEmpty())) {
                reverseFacts(c);
                c.revision++;
                runs.saveSync(run);
            }
        }
    }
    private GeneratedQuestion forward(GeneratedQuestion q) {
        if (q.getParams() == null || q.getParams().getA() == null || q.getParams().getB() == null)
            throw new IllegalStateException("Correction fact has no operands");
        return helper.buildQuestionObject(q.getOperation(), q.getLevel(), "correction", q.getParams().getA(), q.getParams().getB(), "correction", null);
    }
    static void add(Map<String,GeneratedQuestion> pool, GeneratedQuestion q) {
        pool.putIfAbsent(q.getOperation()+":"+q.getParams().getA()+":"+q.getParams().getB(), q);
    }
    public record State(String id, long revision, String phase, GeneratedQuestion question, int position, long elapsedMs, long limitMs) {}
    public State state(QuizRun run) {
        ensureRocketFormat(run);
        var c = run.correctionLoop;
        if (c == null) throw new BadRequestException("No correction is pending");
        int index = "practice".equals(c.phase) ? c.order.get(c.position) : c.shown;
        return new State(c.id,c.revision,c.phase,c.facts.get(index),c.position,c.elapsedMs,c.limitMs);
    }
    public State act(QuizRun run, String id, long revision, String action, Integer answer, long responseMs) {
        synchronized (run) {
            ensureRocketFormat(run);
            var c = run.correctionLoop;
            if (c == null || !Objects.equals(c.id,id)) throw new BadRequestException("Correction changed; reload it");
            if (revision < c.revision) { runs.saveSync(run); return state(run); } // Retry, including an interrupted persistence acknowledgement.
            if (revision != c.revision || responseMs < 0) throw new BadRequestException("Invalid correction revision or time");
            apply(c, action, answer, responseMs);
            c.revision++;
            runs.saveSync(run);
            return state(run);
        }
    }
    static void apply(QuizRun.CorrectionLoop c, String action, Integer answer, long ms) {
        if ("next".equals(action) && "answer".equals(c.phase)) {
            c.order = new ArrayList<>(List.of(0,1,2)); Collections.shuffle(c.order);
            c.position=0; c.elapsedMs=0; c.phase="practice";
        } else if (action != null && Set.of("answer","timeout","pause").contains(action) && "practice".equals(c.phase)) {
            c.elapsedMs = Math.min(c.limitMs + 1, c.elapsedMs + Math.min(ms,c.limitMs + 1));
            c.shown = c.order.get(c.position);
            if ("pause".equals(action) && c.elapsedMs <= c.limitMs) return;
            if (!"answer".equals(action) || !Objects.equals(answer,c.facts.get(c.shown).getCorrectAnswer()) || c.elapsedMs > c.limitMs) {
                c.phase="answer"; c.position=0;
            } else if (++c.position == 3) c.phase="passed";
        } else throw new BadRequestException("Invalid correction action");
    }
}
