package com.infinityisland.service;

import com.infinityisland.dao.AssessmentAttempt;
import com.infinityisland.dao.AssessmentAttempt.Item;
import com.infinityisland.dao.AssessmentAttempt.Answer;
import com.infinityisland.dao.Catalog;
import com.infinityisland.dao.user.User;
import jakarta.annotation.PostConstruct;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ClientErrorException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Service
public class AssessmentService {
    // The client checkpoints every 5 seconds. A crashed/closed browser cannot add offline hours.
    static final long LEASE_MS = 15_000;
    private final MongoTemplate mongo;
    public AssessmentService(MongoTemplate mongo) { this.mongo = mongo; }

    @PostConstruct
    public void indexes() {
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("type", Sort.Direction.ASC).unique()
            .partial(PartialIndexFilter.of(Criteria.where("completed").is(false))));
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("beginKey", Sort.Direction.ASC).unique());
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index().on("completedAt", Sort.Direction.DESC));
    }

    public static List<Item> buildPool(List<Catalog> catalogs) {
        Set<Item> unique = new LinkedHashSet<>();
        for (Catalog catalog : catalogs) {
            if (!"add".equals(catalog.getOperation()) || catalog.getFacts() == null) continue;
            for (Catalog.Fact fact : catalog.getFacts()) {
                if (fact.getA() == null || fact.getB() == null || fact.getA() < 0 || fact.getB() < 0)
                    throw new IllegalStateException("Invalid addition curriculum fact");
                Item item = new Item(fact.getA(), fact.getB());
                item.result(); // Validate overflow before creating an attempt.
                unique.add(item);
                unique.add(new Item(item.b(), item.a()));
            }
        }
        return new ArrayList<>(unique);
    }

    public AssessmentAttempt current(String userId) {
        return mongo.findOne(Query.query(Criteria.where("userId").is(userId)
            .and("type").is("A").and("completed").is(false)), AssessmentAttempt.class);
    }

    public AssessmentAttempt start(User user, String beginKey, String session) {
        requireKey(beginKey); requireKey(session);
        AssessmentAttempt attempt = mongo.findOne(Query.query(Criteria.where("userId").is(user.getId())
            .and("beginKey").is(beginKey)), AssessmentAttempt.class);
        if (attempt == null) attempt = current(user.getId());
        if (attempt == null) {
            attempt = new AssessmentAttempt();
            attempt.items = buildPool(mongo.find(Query.query(Criteria.where("operation").is("add")), Catalog.class));
            if (attempt.items.isEmpty()) throw new BadRequestException("Addition curriculum is not available yet");
            Collections.shuffle(attempt.items);
            attempt.userId = user.getId(); attempt.studentName = user.getName(); attempt.studentPin = user.getPin();
            attempt.beginKey = beginKey; attempt.startedAt = new Date();
            try { attempt = mongo.insert(attempt); }
            catch (DuplicateKeyException concurrentStart) {
                attempt = current(user.getId());
                if (attempt == null) throw new ClientErrorException("Please retry Begin", 409);
            }
        }
        return change(user.getId(), attempt.id, a -> {
            if (a.completed) return;
            tick(a, System.currentTimeMillis());
            a.session = session;
            a.lastTick = new Date();
        });
    }

    public AssessmentAttempt update(String userId, String id, String session, int position, String action, String answer) {
        requireKey(session);
        if (action == null || !Set.of("answer", "heartbeat", "pause").contains(action)) throw new BadRequestException("Invalid action");
        if (position < 0) throw new BadRequestException("Invalid question position");
        if ("answer".equals(action) && (answer == null || !answer.matches("[0-9]{1,10}")))
            throw new BadRequestException("Enter a numeric answer (up to 10 digits)");
        return change(userId, id, a -> applyUpdate(a, session, position, action, answer, System.currentTimeMillis()));
    }

    static void applyUpdate(AssessmentAttempt a, String session, int position, String action, String answer, long now) {
        if (a.completed) return;
        if (!Objects.equals(a.session, session)) throw new ClientErrorException("Test opened in another tab. Resume here to continue.", 409);
        // A retry of an accepted answer must not answer the next question or count its time twice.
        if (position < a.position) return;
        if (position != a.position) throw new ClientErrorException("Question changed. Resume the test.", 409);
        tick(a, now);
        if ("pause".equals(action)) { a.lastTick = null; return; }
        if ("heartbeat".equals(action)) { a.lastTick = new Date(now); return; }
        Item item = a.items.get(a.position);
        boolean correct = Long.parseLong(answer) == item.result();
        a.answers.add(new Answer(item.problem(), answer, correct, a.questionMs));
        if (correct) a.correctCount++;
        a.totalMs += a.questionMs;
        a.questionMs = 0;
        a.position++;
        a.completed = a.position == a.items.size();
        if (a.completed) { a.completedAt = new Date(now); a.lastTick = null; }
    }

    static void tick(AssessmentAttempt a, long now) {
        if (a.lastTick != null) {
            long elapsed = now - a.lastTick.getTime();
            // Beyond the lease the browser may have closed. Preserve confirmed time, not the unknown gap.
            if (elapsed > 0 && elapsed <= LEASE_MS) a.questionMs += elapsed;
            a.lastTick = new Date(now);
        }
    }

    private AssessmentAttempt change(String userId, String id, Consumer<AssessmentAttempt> mutation) {
        for (int retry = 0; retry < 8; retry++) {
            AssessmentAttempt a = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("userId").is(userId)), AssessmentAttempt.class);
            if (a == null) throw new NotFoundException("Test not found");
            mutation.accept(a);
            try { return mongo.save(a); }
            catch (OptimisticLockingFailureException conflict) { /* Reload, then recheck question/session. */ }
        }
        throw new ClientErrorException("Test is busy. Please retry.", 409);
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9-]{1,80}")) throw new BadRequestException("Invalid request identifier");
    }

    public record StudentState(String id, String type, boolean completed, int position, int count, String problem, String nextProblem) {}
    public static StudentState student(AssessmentAttempt a) {
        return a == null ? null : new StudentState(a.id, a.type, a.completed, a.position, a.items.size(),
            a.completed ? null : a.items.get(a.position).problem(),
            !a.completed && a.position + 1 < a.items.size() ? a.items.get(a.position + 1).problem() : null);
    }

    public Query reportQuery(String pin, String type, String from, String to) {
        Criteria criteria = Criteria.where("completed").is(true);
        if (pin != null && !pin.isBlank()) criteria.and("studentPin").is(pin.trim());
        if (type != null && !type.isBlank()) criteria.and("type").is(type);
        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            Criteria date = criteria.and("completedAt");
            try {
                if (from != null && !from.isBlank()) date.gte(Date.from(Instant.parse(from)));
                if (to != null && !to.isBlank()) date.lt(Date.from(Instant.parse(to)));
            } catch (RuntimeException invalid) { throw new BadRequestException("Invalid date range"); }
        }
        return Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "completedAt", "_id"));
    }

    public List<AssessmentAttempt> reports(Query query, int page) {
        return mongo.find(query.skip((long) Math.max(0, page) * 25).limit(25), AssessmentAttempt.class);
    }
    public Stream<AssessmentAttempt> export(Query query) { return mongo.stream(query, AssessmentAttempt.class); }
}
