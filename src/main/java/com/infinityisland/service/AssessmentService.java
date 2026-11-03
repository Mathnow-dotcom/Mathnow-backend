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
    @org.springframework.beans.factory.annotation.Autowired private ProgramService program;
    public AssessmentService(MongoTemplate mongo) { this.mongo = mongo; }

    static final java.time.ZoneId TEST_ZONE = java.time.ZoneId.of("America/Los_Angeles");
    static String testDay(java.time.Instant instant) { return instant.atZone(TEST_ZONE).toLocalDate().toString(); }
    static Query dailyAttemptQuery(String userId, String type, Integer part, java.time.Instant now) {
        var day = now.atZone(TEST_ZONE).toLocalDate();
        return Query.query(Criteria.where("userId").is(userId).and("type").is(type)
            .and("programBatch").is(null).and("legacyPart").is(part)
            .and("startedAt").gte(Date.from(day.atStartOfDay(TEST_ZONE).toInstant()))
            .lt(Date.from(day.plusDays(1).atStartOfDay(TEST_ZONE).toInstant())));
    }
    private void checkDailyAttempt(String userId, String type, Integer part, java.time.Instant now) {
        if (mongo.exists(dailyAttemptQuery(userId, type, part, now), AssessmentAttempt.class))
            throw new ClientErrorException(jakarta.ws.rs.core.Response.status(409)
                .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                .entity(Map.of("error", "Try again tomorrow.",
                    "code", "HIGH_STAKES_DAILY_LIMIT")).build());
    }

    @PostConstruct
    public void indexes() {
        // Idempotently classify unfinished records only; historical results stay untouched.
        mongo.updateMulti(Query.query(Criteria.where("completed").is(false).and("programBatch").ne(null)),
            new org.springframework.data.mongodb.core.query.Update().set("flow", "learning"), AssessmentAttempt.class);
        mongo.updateMulti(Query.query(Criteria.where("completed").is(false).and("programBatch").is(null)),
            new org.springframework.data.mongodb.core.query.Update().set("flow", "legacy"), AssessmentAttempt.class);
        mongo.updateMulti(Query.query(Criteria.where("completed").is(false).and("abandoned").ne(true)),
            new org.springframework.data.mongodb.core.query.Update().set("active", true), AssessmentAttempt.class);
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("type", Sort.Direction.ASC).on("flow", Sort.Direction.ASC).on("legacyPart", Sort.Direction.ASC).unique()
            .named("active_assessment_sessions")
            .partial(PartialIndexFilter.of(Criteria.where("active").is(true))));
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("programEnrollment", Sort.Direction.ASC)
            .on("programSubject", Sort.Direction.ASC).on("programBatch", Sort.Direction.ASC)
            .named("completed_learning_batch").unique().partial(PartialIndexFilter.of(
                Criteria.where("programBatch").exists(true).and("completed").is(true))));
        // Install the replacement constraint before removing the former cross-flow constraint.
        for (var index : mongo.indexOps(AssessmentAttempt.class).getIndexInfo()) {
            var fields = index.getIndexFields().stream().map(f -> f.getKey()).toList();
            if (index.isUnique() && (fields.equals(List.of("userId", "type")) || fields.equals(List.of("userId", "type", "flow"))
                || fields.equals(List.of("userId", "type", "flow", "legacyPart")) && !"active_assessment_sessions".equals(index.getName())
                || fields.equals(List.of("userId", "programEnrollment", "programSubject", "programBatch")) && !"completed_learning_batch".equals(index.getName())))
                mongo.indexOps(AssessmentAttempt.class).dropIndex(index.getName());
        }
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("beginKey", Sort.Direction.ASC).unique());
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index().on("completedAt", Sort.Direction.DESC));
        mongo.indexOps(AssessmentAttempt.class).ensureIndex(new Index()
            .on("userId", Sort.Direction.ASC).on("type", Sort.Direction.ASC)
            .on("legacyPart", Sort.Direction.ASC).on("highStakesDay", Sort.Direction.ASC)
            .named("one_high_stakes_test_per_day").unique()
            .partial(PartialIndexFilter.of(Criteria.where("highStakesDay").exists(true))));


    }

    public static List<Item> buildPool(List<Catalog> catalogs) {
        return buildPool(catalogs, "A");
    }

    static String testType(String type) {
        if (type == null) return "A";
        if (!Set.of("A", "B", "C", "D").contains(type)) throw new BadRequestException("Invalid test type");
        return type;
    }

    private static String operation(String type) {
        return switch (testType(type)) { case "B" -> "sub"; case "C" -> "mul"; case "D" -> "div"; default -> "add"; };
    }

    public static List<Item> buildPool(List<Catalog> catalogs, String type) {
        String operation = operation(type);
        Set<Item> unique = new LinkedHashSet<>();
        for (Catalog catalog : catalogs) {
            if (!operation.equals(catalog.getOperation()) || catalog.getFacts() == null) continue;
            for (Catalog.Fact fact : catalog.getFacts()) {
                if (fact.getA() == null || fact.getB() == null || fact.getA() < 0 || fact.getB() < 0)
                    throw new IllegalStateException("Invalid curriculum fact");
                Item item = new Item(fact.getA(), fact.getB());
                if ("div".equals(operation)) DivisionCatalogSeeder.validateDivisionFact(item.a(), item.b());
                if (item.result(type) < 0) throw new IllegalStateException("Negative subtraction curriculum answer");
                unique.add(item);
                if ("add".equals(operation) || "mul".equals(operation)) unique.add(new Item(item.b(), item.a()));
            }
        }
        return new ArrayList<>(unique);
    }

    public AssessmentAttempt current(String userId) {
        return current(userId, "A");
    }
    public AssessmentAttempt current(String userId, String type) {
        return current(userId, type, "learning");
    }
    static String testFlow(String flow) {
        if (flow == null) return "learning"; // Existing clients keep their learning semantics.
        if (!Set.of("learning", "legacy").contains(flow)) throw new BadRequestException("Invalid test flow");
        return flow;
    }
    public AssessmentAttempt current(String userId, String type, String requestedFlow) {
        return current(userId, type, requestedFlow, null);
    }
    public AssessmentAttempt current(String userId, String type, String requestedFlow, Integer part) {
        String flow = testFlow(requestedFlow);
        validatePart(type, flow, part);
        Criteria criteria = Criteria.where("userId").is(userId)
            .and("type").is(testType(type)).and("completed").is(false).and("abandoned").ne(true);
        if ("legacy".equals(flow)) criteria.and("programBatch").is(null).and("legacyPart").is(part);
        else criteria.and("programBatch").ne(null);
        return mongo.findOne(Query.query(criteria), AssessmentAttempt.class);
    }

    public AssessmentAttempt start(User user, String beginKey, String session) {
        return start(user, beginKey, session, "A");
    }
    public AssessmentAttempt start(User user, String beginKey, String session, String requestedType) {
        return start(user, beginKey, session, requestedType, "learning");
    }
    public AssessmentAttempt start(User user, String beginKey, String session, String requestedType, String requestedFlow) {
        return start(user, beginKey, session, requestedType, requestedFlow, null);
    }
    static void validatePart(String type, String flow, Integer part) {
        if (part != null && (!"A".equals(testType(type)) || !"legacy".equals(flow) || (part != 1 && part != 2)))
            throw new BadRequestException("Invalid addition test part");
    }
    public AssessmentAttempt start(User user, String beginKey, String session, String requestedType, String requestedFlow, Integer part) {
        String type = testType(requestedType);
        String flow = testFlow(requestedFlow);
        validatePart(type, flow, part);
        requireKey(beginKey); requireKey(session);
        AssessmentAttempt attempt = mongo.findOne(Query.query(Criteria.where("userId").is(user.getId())
            .and("beginKey").is(beginKey)), AssessmentAttempt.class);
        if (attempt != null && !type.equals(attempt.type)) throw new BadRequestException("Request identifier belongs to another test");
        if (attempt != null && !flow.equals(attempt.programBatch == null ? "legacy" : "learning"))
            throw new BadRequestException("Request identifier belongs to another test flow");
        if (attempt != null && !Objects.equals(part, attempt.legacyPart)) throw new BadRequestException("Request identifier belongs to another test part");
        if (attempt != null && !Objects.equals(attempt.session, session))
            throw new ClientErrorException("This test session has ended. Start a new test.", 409);
        if (attempt == null && "legacy".equals(flow))
            checkDailyAttempt(user.getId(), type, part, java.time.Instant.now());
        if (attempt == null) {
            var previous = current(user.getId(), type, flow, part);
            if (previous != null) change(user.getId(), previous.id, a -> abandon(a, System.currentTimeMillis()));
        }
        if (attempt == null) {
            var eligible = "learning".equals(flow) ? program.requireAssessment(user.getId(), operation(type)) : null;
            attempt = new AssessmentAttempt();
            attempt.type = type;
            attempt.flow = flow;
            attempt.active = true;
            attempt.session = session;
            attempt.legacyPart = part;
            if (eligible != null) {
                attempt.programSubject = operation(type);
                attempt.programEnrollment = program.assessmentEnrollment(program.get(user.getId()), eligible);
                attempt.programBatch = eligible.batch();
                if (eligible.curriculum().splitAddition()) {
                    attempt.programPart = eligible.curriculum().part(eligible.batch());
                    attempt.programPartBatch = eligible.curriculum().partBatch(eligible.batch());
                }
            }
            Criteria curriculum = Criteria.where("operation").is(operation(type));
            if (eligible != null) curriculum.and("level").in(eligible.curriculum().assessmentLevels(eligible.batch()).stream().map(String::valueOf).toList());
            if (part != null) curriculum.and("level").in(java.util.stream.IntStream.rangeClosed(part == 1 ? 1 : 11, part == 1 ? 10 : 19).mapToObj(String::valueOf).toList());
            attempt.items = buildPool(mongo.find(Query.query(curriculum), Catalog.class), type);
            if (attempt.items.isEmpty()) throw new BadRequestException("Test curriculum is not available yet");
            Collections.shuffle(attempt.items);
            attempt.userId = user.getId(); attempt.studentName = user.getName(); attempt.studentPin = user.getPin();
            attempt.beginKey = beginKey; attempt.startedAt = new Date(); attempt.lastTick = attempt.startedAt;
            if ("legacy".equals(flow)) {
                // Recheck at insertion time in case pool generation crossed midnight.
                checkDailyAttempt(user.getId(), type, part, attempt.startedAt.toInstant());
                attempt.highStakesDay = testDay(attempt.startedAt.toInstant());
            }
            try { attempt = mongo.insert(attempt); }
            catch (DuplicateKeyException concurrentStart) {
                attempt = mongo.findOne(Query.query(Criteria.where("userId").is(user.getId()).and("beginKey").is(beginKey)), AssessmentAttempt.class);
                if (attempt == null && "legacy".equals(flow))
                    checkDailyAttempt(user.getId(), type, part, java.time.Instant.now());
                if (attempt == null || !Objects.equals(attempt.session, session)) throw new ClientErrorException("Please retry Start", 409);
            }
        }
        return change(user.getId(), attempt.id, a -> {
            if (a.completed || a.abandoned) return;
            long now = System.currentTimeMillis();
            if (a.lastTick == null || now - a.lastTick.getTime() > LEASE_MS) { abandon(a, now); return; }
            tick(a, now);
            a.session = session;
            a.lastTick = new Date();
        });
    }

    public AssessmentAttempt update(String userId, String id, String session, int position, String action, String answer) {
        requireKey(session);
        if (action == null || !Set.of("answer", "heartbeat", "pause", "end").contains(action)) throw new BadRequestException("Invalid action");
        if (position < 0) throw new BadRequestException("Invalid question position");
        if ("answer".equals(action) && (answer == null || !answer.matches("[0-9]{1,10}")))
            throw new BadRequestException("Enter a numeric answer (up to 10 digits)");
        AssessmentAttempt saved = change(userId, id, a -> applyUpdate(a, session, position, action, answer, System.currentTimeMillis()));
        if (saved.completed && saved.programBatch != null) program.refresh(userId);
        return saved;
    }

    static void applyUpdate(AssessmentAttempt a, String session, int position, String action, String answer, long now) {
        if (a.completed || a.abandoned) return;
        if (!Objects.equals(a.session, session)) throw new ClientErrorException("This test session has ended. Start a new test.", 409);
        if ("end".equals(action) || "pause".equals(action)) { abandon(a, now); return; }
        if (a.lastTick != null && now - a.lastTick.getTime() > LEASE_MS) { abandon(a, now); return; }
        // A retry of an accepted answer must not answer the next question or count its time twice.
        if (position < a.position) return;
        if (position != a.position) throw new ClientErrorException("Question changed. Retry the request.", 409);
        tick(a, now);
        if ("heartbeat".equals(action)) { a.lastTick = new Date(now); return; }
        Item item = a.items.get(a.position);
        boolean correct = Long.parseLong(answer) == item.result(a.type);
        a.answers.add(new Answer(item.problem(a.type), answer, correct, a.questionMs, new Date(now)));
        if (correct) a.correctCount++;
        a.totalMs += a.questionMs;
        a.questionMs = 0;
        a.position++;
        a.completed = a.position == a.items.size();
        if (a.completed) {
            a.completedAt = new Date(now); a.endedAt = a.completedAt; a.active = false; a.lastTick = null;
            if (a.programBatch != null)
                a.programPassed = a.correctCount == a.items.size() && a.totalMs < 3000L * a.items.size();
        }
    }

    static void abandon(AssessmentAttempt a, long now) {
        if (a.completed || a.abandoned) return;
        tick(a, now);
        a.abandoned = true; a.active = false; a.endedAt = new Date(now); a.lastTick = null;
    }
    // A closed browser may never deliver its final request. Preserve it as unfinished, never as a failed batch.
    private void expireStale() {
        var stale = Query.query(Criteria.where("completed").is(false).and("abandoned").ne(true)
            .orOperator(Criteria.where("lastTick").lt(new Date(System.currentTimeMillis()-LEASE_MS)),
                Criteria.where("lastTick").is(null)));
        mongo.updateMulti(stale, new org.springframework.data.mongodb.core.query.Update()
            .set("abandoned", true).set("active", false).set("endedAt", new Date()).unset("lastTick").inc("version", 1), AssessmentAttempt.class);
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

    public record StudentState(String id, String type, boolean completed, int position, int count, String problem, String nextProblem, Integer part, Integer partBatch, boolean abandoned) {}
    public static StudentState student(AssessmentAttempt a) {
        return a == null ? null : new StudentState(a.id, a.type, a.completed, a.position, a.items.size(),
            (a.completed || a.abandoned) ? null : a.items.get(a.position).problem(a.type),
            !a.completed && !a.abandoned && a.position + 1 < a.items.size() ? a.items.get(a.position + 1).problem(a.type) : null,
            a.legacyPart != null ? a.legacyPart : a.programPart, a.programPartBatch, a.abandoned);
    }

    public Query reportQuery(String pin, String type, String from, String to) {
        Criteria criteria = new Criteria();
        if (pin != null && !pin.isBlank()) criteria.and("studentPin").is(pin.trim());
        if (type != null && !type.isBlank()) criteria.and("type").is(type);
        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            Criteria date = criteria.and("startedAt");
            try {
                if (from != null && !from.isBlank()) date.gte(Date.from(Instant.parse(from)));
                if (to != null && !to.isBlank()) date.lt(Date.from(Instant.parse(to)));
            } catch (RuntimeException invalid) { throw new BadRequestException("Invalid date range"); }
        }
        return Query.query(criteria).with(Sort.by(Sort.Direction.DESC, "startedAt", "_id"));
    }

    public List<AssessmentAttempt> reports(Query query, int page) {
        expireStale();
        return mongo.find(query.skip((long) Math.max(0, page) * 25).limit(25), AssessmentAttempt.class);
    }
    public Stream<AssessmentAttempt> export(Query query) { expireStale(); return mongo.stream(query, AssessmentAttempt.class); }
}
