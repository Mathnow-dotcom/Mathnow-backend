package com.infinityisland.service;

import com.infinityisland.dao.*;
import com.infinityisland.dao.user.User;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import jakarta.ws.rs.ClientErrorException;
import java.util.*;
import java.util.stream.IntStream;
import static com.infinityisland.service.ProgramProgressionRules.*;

@Service
public class ProgramService {
    private final MongoTemplate mongo;
    private final CachedUserService users;
    private final GameConfigService config;
    public ProgramService(MongoTemplate mongo, CachedUserService users, GameConfigService config) {
        this.mongo = mongo; this.users = users; this.config = config;
    }
    public boolean managed(String op) { return SUBJECTS.contains(op); }

    /** Read-only planner. Every configured level must have actual curriculum facts. */
    public Curriculum curriculum(String op) {
        int size = config.getMaxLevel(op);
        Set<Integer> levels = new HashSet<>();
        Set<String> slots = new HashSet<>();
        for (Catalog c : mongo.find(Query.query(Criteria.where("operation").is(op)), Catalog.class)) {
            if (c.getFacts() != null && !c.getFacts().isEmpty()) {
                try {
                    levels.add(Integer.parseInt(c.getLevel()));
                    slots.add(c.getLevel() + ":" + c.getBelt());
                }
                catch (NumberFormatException invalid) { throw new IllegalStateException("Invalid curriculum level"); }
            }
        }
        for (int l = 1; l <= size; l++)
            for (String belt : com.infinityisland.model.Belt.COLORED_ORDER)
                if (!levels.contains(l) || !slots.contains(l + ":" + belt))
                    throw new IllegalStateException("Missing curriculum: " + op + " level " + l + " " + belt);
        List<Integer> base = IntStream.rangeClosed(1, size).boxed().toList();
        return "add".equals(op) && size > 10 ? new Curriculum(base, 0, null, null) : new Curriculum(base);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> levels(User user, String op) {
        Map<String,Object> p = user.getProgress();
        if (p == null) return Map.of();
        Object n = p.get(op);
        if (n instanceof Map) return (Map<String,Object>) n;
        if (op.equals("add") && p.keySet().stream().anyMatch(k -> k.matches("L[0-9]+"))) return p;
        return Map.of();
    }
    private Set<Integer> completed(User user, String op, int max) {
        Set<Integer> result = new HashSet<>();
        Map<String,Object> levels = levels(user, op);
        for (int l = 1; l <= max; l++)
            if (levels.get("L"+l) instanceof Map<?,?> n && Boolean.TRUE.equals(n.get("completed"))) result.add(l);
        return result;
    }
    /** Additive lazy migration; never edits a user, old assessment or quiz document. */
    public ProgramProgress get(String userId) {
        ProgramProgress p = mongo.findById(userId, ProgramProgress.class);
        if (p != null) {
            State old = p.subjects.get("add");
            if ("add".equals(p.activeSubject) && old != null && !old.curriculum().splitAddition()
                && old.curriculum().batchSize() > 10 && old.status() != Status.PASSED) {
                User u = users.findById(userId).orElseThrow();
                p.subjects.put("add", splitAdditionMigration(u, old.curriculum().baseLevels(), old));
                try { return mongo.save(p); }
                catch (OptimisticLockingFailureException conflict) {
                    throw new ClientErrorException("Progress changed. Please retry.", 409);
                }
            }
            return p;
        }
        User u = users.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        p = plan(u);
        try { return mongo.insert(p); }
        catch (DuplicateKeyException concurrent) { return mongo.findById(userId, ProgramProgress.class); }
    }
    /** Read-only migration preview, also used by reporting for students who have not returned yet. */
    public ProgramProgress plan(User u) {
        ProgramProgress p = new ProgramProgress(); p.id = u.getId(); p.migratedAt = new Date();
        int active = 0;
        for (int i = 0; i < SUBJECTS.size(); i++) {
            String op = SUBJECTS.get(i);
            boolean accessed = levels(u, op).values().stream().anyMatch(n ->
                n instanceof Map<?,?> m && (Boolean.TRUE.equals(m.get("unlocked")) || Boolean.TRUE.equals(m.get("completed"))));
            String prerequisite = config.getOperationPrerequisite(op);
            boolean legacyAllowed = config.getMaxLevel(op) > 0 && (prerequisite == null ||
                completed(u, prerequisite, config.getMaxLevel(prerequisite)).size() == config.getMaxLevel(prerequisite));
            if (!Boolean.TRUE.equals(u.getIntegratedEnrollment()) && (accessed || legacyAllowed)) p.grandfatheredAccess.add(op);
            if (accessed) active = i;
        }
        p.grandfatheredAccess.add("add");
        // A completed legacy subject must not pull a student back from an already available successor.
        while (active + 1 < SUBJECTS.size() && p.grandfatheredAccess.contains(SUBJECTS.get(active + 1)) &&
            completed(u, SUBJECTS.get(active), config.getMaxLevel(SUBJECTS.get(active))).size() == config.getMaxLevel(SUBJECTS.get(active))) active++;
        p.activeSubject = SUBJECTS.get(active);
        Curriculum c = curriculum(p.activeSubject);
        p.subjects.put(p.activeSubject, c.splitAddition() ? splitAdditionMigration(u, c.baseLevels(), null)
            : importCurrentSubject(p.activeSubject, c, completed(u, p.activeSubject, c.batchSize())));
        return p;
    }
    /** Idempotent conversion: keep all saved nodes/results, never reinterpret old repeats. */
    private State splitAdditionMigration(User u, List<Integer> base, State old) {
        int highest = levels(u, "add").entrySet().stream().filter(e -> e.getKey().matches("L[0-9]+") &&
            e.getValue() instanceof Map<?,?> n && (Boolean.TRUE.equals(n.get("unlocked")) || Boolean.TRUE.equals(n.get("completed"))))
            .mapToInt(e -> Integer.parseInt(e.getKey().substring(1))).max().orElse(1);
        if (old != null) highest = Math.max(highest, old.currentLevel());
        boolean part2 = highest > 10;
        // First-cycle students retain L11–L19 including partial belt progress. Old
        // repeat batches retain their original mapping and get a fresh Part 2 range.
        int start = !part2 ? 0 : highest <= base.size() ? 11 : highest + 1;
        Curriculum c = new Curriculum(base, start, part2 ? start - 1 : null, part2 ? base.size() : null);
        Set<Integer> done = new HashSet<>(completed(u, "add", part2 ? highest : 10));
        if (old != null) done.addAll(old.completedLevels());
        int batch = part2 ? 11 : 1;
        boolean ready = IntStream.rangeClosed(c.firstLevel(batch), c.lastLevel(batch)).allMatch(done::contains);
        return new State("add", c, batch, ready ? Status.ASSESSMENT_READY : Status.LEARNING,
            done, old == null ? Map.of() : old.results());
    }
    public String assessmentEnrollment(ProgramProgress p, State state) {
        return p.enrollment + (state.curriculum().splitAddition() ? ":addition-parts-v1" : "");
    }
    /** Reconcile only persisted learning completions, and recover committed assessment transitions. */
    public ProgramProgress refresh(String userId) {
        for (int retry = 0; retry < 8; retry++) {
            ProgramProgress p = get(userId);
            if (p.activeSubject == null) return p;
            State s = p.subjects.get(p.activeSubject);
            User u = mongo.findById(userId, User.class);
            if (u == null) throw new IllegalArgumentException("User not found");
            Set<Integer> done = completed(u, s.subject(), s.curriculum().lastLevel());
            State next = s;
            while (next.status() == Status.LEARNING && done.contains(next.currentLevel()))
                next = completeLevel(next, next.currentLevel());
            // Completed assessment documents are the durable transition journal.
            if (next.status() == Status.ASSESSMENT_READY) {
                var q = Query.query(Criteria.where("userId").is(userId).and("programBatch").is(next.batch())
                    .and("programSubject").is(next.subject()).and("programEnrollment").is(assessmentEnrollment(p, next)).and("completed").is(true));
                AssessmentAttempt a = mongo.findOne(q, AssessmentAttempt.class);
                if (a != null) next = finishAssessment(next, a.id, a.programBatch, a.items.size(),
                    a.answers.stream().map(x -> new ItemOutcome(x.correct(), x.timeMs())).toList());
            }
            if (next.equals(s)) return p;
            p.subjects.put(s.subject(), next);
            if (next.status() == Status.PASSED) {
                p.activeSubject = nextSubject(next).orElse(null);
                if (p.activeSubject != null && !p.subjects.containsKey(p.activeSubject)) {
                    Curriculum c = curriculum(p.activeSubject);
                    p.subjects.put(p.activeSubject, importCurrentSubject(p.activeSubject, c,
                        completed(u, p.activeSubject, c.batchSize())));
                }
            }
            try { mongo.save(p); }
            catch (OptimisticLockingFailureException conflict) { continue; }
            return refresh(userId);
        }
        throw new ClientErrorException("Progress is busy. Please retry.", 409);
    }
    public boolean unlocked(String user, String op) {
        ProgramProgress p = refresh(user);
        return p.grandfatheredAccess.contains(op) || p.subjects.containsKey(op);
    }
    public record ArchivedProgram(@org.springframework.data.annotation.Id String id, ProgramProgress progress, Date archivedAt) {}
    /** Called only by the existing explicit admin reset/restore/delete actions, never by migration. */
    public void archiveForAdminChange(String user) {
        ProgramProgress p = mongo.findById(user, ProgramProgress.class);
        if (p == null) return;
        try { mongo.insert(new ArchivedProgram(user + ":" + p.enrollment, p, new Date()), "program_progress_history"); }
        catch (DuplicateKeyException alreadyArchived) { /* Retry of the same admin action. */ }
        mongo.remove(Query.query(Criteria.where("_id").is(user).and("version").is(p.version)), ProgramProgress.class);
    }
    public int contentLevel(String user, String op, int cumulative) {
        if (!managed(op)) return cumulative;
        State s = get(user).subjects.get(op);
        return s == null ? cumulative : s.curriculum().baseLevel(cumulative);
    }
    public int visibleMax(String user, String op) {
        State s = get(user).subjects.get(op);
        return s == null ? config.getMaxLevel(op) : s.curriculum().lastLevel(s.batch());
    }
    public int batchSize(String user, String op) {
        State s = get(user).subjects.get(op);
        return s == null ? config.getMaxLevel(op) : s.curriculum().batchSize(s.batch());
    }
    public Map<Integer,Integer> contentLevels(String user, String op) {
        State s = get(user).subjects.get(op);
        if (s == null || !s.curriculum().splitAddition()) return Map.of();
        Map<Integer,Integer> mapping = new LinkedHashMap<>();
        for (int l = 1; l <= s.curriculum().lastLevel(s.batch()); l++) mapping.put(l, s.curriculum().baseLevel(l));
        return mapping;
    }
    public void requireLearning(String user, String op, int level) {
        if (!managed(op)) return;
        requireLearning(refresh(user), user, op, level);
    }
    /** Existing runs cannot unlock another level. Validate against persisted program
     * state without re-scanning learning/assessment history on every answer.
     * Prepare and assessment entry still reconcile before allowing new work. */
    public void requireRunAccess(String user, String op, int level, String belt, boolean pretest) {
        if (!managed(op)) return;
        requireLearning(get(user), user, op, level);
        if (!pretest) requireBeltUnlock(user, op, level, belt);
    }
    private void requireLearning(ProgramProgress p, String user, String op, int level) {
        if (!p.grandfatheredAccess.contains(op) && !p.subjects.containsKey(op)) throw new ClientErrorException("Subject is locked", 403);
        State s = p.subjects.get(op);
        if (s == null) {
            if (level < 1 || level > config.getMaxLevel(op)) throw new ClientErrorException("Level is locked", 403);
            // Grandfather access does not allow skipping previously locked levels.
            User u = users.findById(user).orElseThrow();
            if (level != 1 && !(levels(u,op).get("L"+level) instanceof Map<?,?> n &&
                (Boolean.TRUE.equals(n.get("unlocked")) || Boolean.TRUE.equals(n.get("completed")))))
                throw new ClientErrorException("Level is locked", 403);
            return;
        }
        if (s.completedLevels().contains(level)) return; // review remains available
        int legacyLimit = s.curriculum().splitAddition() ? Objects.requireNonNullElse(s.curriculum().legacyThrough(), 0) : s.curriculum().batchSize();
        if (s.curriculum().splitAddition() && s.curriculum().legacyThrough() != null)
            legacyLimit = Math.max(legacyLimit, s.curriculum().lastLevel(11));
        if (p.grandfatheredAccess.contains(op) && level >= 1 && level <= legacyLimit &&
            levels(users.findById(user).orElseThrow(),op).get("L"+level) instanceof Map<?,?> n &&
            Boolean.TRUE.equals(n.get("unlocked"))) return;
        if (s.status() != Status.LEARNING || s.currentLevel() != level)
            throw new ClientErrorException("Complete the current learning batch or assessment first", 403);
    }
    public State requireAssessment(String user, String op) {
        ProgramProgress p = refresh(user);
        State s = p.subjects.get(op);
        if (!Objects.equals(p.activeSubject, op) || s == null || s.status() != Status.ASSESSMENT_READY)
            throw new ClientErrorException("Complete the full learning batch before taking this assessment", 403);
        return s;
    }
    public void requireBelt(String user, String op, int level, String belt) {
        if (!managed(op) || "pretest".equalsIgnoreCase(belt)) return;
        requireLearning(user, op, level);
        requireBeltUnlock(user, op, level, belt);
    }
    private void requireBeltUnlock(String user, String op, int level, String belt) {
        // A newly unlocked subject may still have a locked baseline L1 in legacy progress.
        // White is the entry belt; subsequent belts continue to use existing saved unlocks.
        if ("white".equalsIgnoreCase(belt)) return;
        User u = users.findById(user).orElseThrow();
        Object raw = levels(u, op).get("L"+level);
        Map<?,?> node = raw instanceof Map<?,?> n ? n :
            com.infinityisland.util.ProgressDefaults.createLevelNode(level, true);
        if (Boolean.TRUE.equals(node.get("completed"))) return;
        String key = belt.toLowerCase(Locale.ROOT);
        if (key.startsWith("black-")) {
            int degree;
            try { degree = Integer.parseInt(key.substring(6)); }
            catch (NumberFormatException e) { throw new ClientErrorException("Invalid degree", 400); }
            if (!(node.get("black") instanceof Map<?,?> black) ||
                !Boolean.TRUE.equals(black.get("unlocked")) || degree < 1 || degree > 7)
                throw new ClientErrorException("Belt is locked", 403);
            if (degree > 1 && (!(black.get("completedDegrees") instanceof Collection<?> done) ||
                done.stream().noneMatch(d -> d instanceof Number n && n.intValue() == degree - 1)))
                throw new ClientErrorException("Complete the preceding degree first", 403);
        } else if (!(node.get(key) instanceof Map<?,?> b) || !Boolean.TRUE.equals(b.get("unlocked"))) {
            throw new ClientErrorException("Belt is locked", 403);
        }
    }
    public Map<String,Object> view(String user) {
        ProgramProgress p = refresh(user);
        return view(p, users.findById(user).orElseThrow());
    }
    private Map<String,Object> view(ProgramProgress p, User u) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("subject", p.activeSubject);
        out.put("status", p.activeSubject == null ? "PROGRAM_COMPLETED" : p.subjects.get(p.activeSubject).status().name());
        State s = p.activeSubject == null ? null : p.subjects.get(p.activeSubject);
        out.put("level", s == null ? null : s.currentLevel());
        out.put("batch", s == null ? null : s.batch());
        out.put("part", s == null ? null : s.curriculum().part(s.batch()));
        out.put("partBatch", s == null ? null : s.curriculum().partBatch(s.batch()));
        out.put("assessmentLevels", s == null ? List.of() : s.curriculum().assessmentLevels(s.batch()));
        out.put("subjects", p.subjects);
        out.put("grandfatheredAccess", p.grandfatheredAccess);
        Map<String,Set<Integer>> completedLevels = new LinkedHashMap<>();
        for (String op : SUBJECTS) {
            State state = p.subjects.get(op);
            Set<Integer> done = new TreeSet<>(completed(u, op, state == null ? config.getMaxLevel(op) : state.curriculum().lastLevel()));
            if (state != null) done.addAll(state.completedLevels());
            completedLevels.put(op, done);
        }
        out.put("completedLevels", completedLevels);
        if (continuesFractions(p, u)) {
            out.put("subject", "frac");
            out.put("status", "LEARNING");
            out.put("level", fractionLevel(u));
            out.put("batch", null); // Fractions still uses the original curriculum, not batches.
            out.put("part", null);
            out.put("partBatch", null);
            out.put("assessmentLevels", List.of());
        }
        return out;
    }
    private boolean continuesFractions(ProgramProgress p, User u) {
        if (!config.isOperationEnabled("frac")) return false;
        if (p.activeSubject == null) return true;
        // Preserve pre-existing access, including students already lazily migrated
        // into a Division state. Do not manufacture assessment passes or reset them.
        return !Boolean.TRUE.equals(u.getIntegratedEnrollment()) && levels(u, "frac").values().stream()
            .anyMatch(n -> n instanceof Map<?,?> m &&
                (Boolean.TRUE.equals(m.get("unlocked")) || Boolean.TRUE.equals(m.get("completed"))));
    }
    private int fractionLevel(User u) {
        int highest = 1, incomplete = 0;
        for (var entry : levels(u, "frac").entrySet()) {
            if (!entry.getKey().matches("L[0-9]+") || !(entry.getValue() instanceof Map<?,?> node) ||
                !Boolean.TRUE.equals(node.get("unlocked"))) continue;
            int level = Integer.parseInt(entry.getKey().substring(1));
            highest = Math.max(highest, level);
            if (!Boolean.TRUE.equals(node.get("completed"))) incomplete = Math.max(incomplete, level);
        }
        return incomplete > 0 ? incomplete : highest;
    }
    public Map<String,Object> report(String pin) {
        List<Map<String,Object>> students = new ArrayList<>();
        Map<String,Long> statuses = new LinkedHashMap<>();
        Map<String,Long> modules = new LinkedHashMap<>();
        long passes = 0, failures = 0;
        for (User u : mongo.findAll(User.class)) {
            if (u == null || (pin != null && !pin.isBlank() && !pin.trim().equals(u.getPin()))) continue;
            ProgramProgress existing = mongo.findById(u.getId(), ProgramProgress.class);
            ProgramProgress updated = existing == null ? plan(u) : refresh(u.getId());
            Map<String,Object> row = new LinkedHashMap<>(view(updated, u));
            row.put("migrationPending", existing == null);
            row.put("student", u.getName()); row.put("pin", u.getPin());
            students.add(row);
            statuses.merge(String.valueOf(row.get("status")), 1L, Long::sum);
            modules.merge(Objects.toString(row.get("subject"), "completed"), 1L, Long::sum);
            for (State s : updated.subjects.values()) for (Result r : s.results().values()) {
                if (r.passed()) passes++; else failures++;
            }
        }
        Map<String,Double> moduleRates = new LinkedHashMap<>();
        modules.forEach((op,count) -> moduleRates.put(op, students.isEmpty() ? 0.0 : 100.0 * count / students.size()));
        return Map.of("students", students, "statuses", statuses, "modules", modules, "moduleRates", moduleRates,
            "passes", passes, "failures", failures,
            "interventionRate", students.isEmpty() ? 0.0 : 100.0 * statuses.getOrDefault("INTERVENTION_REQUIRED",0L) / students.size(),
            "passRate", passes + failures == 0 ? 0.0 : 100.0 * passes / (passes + failures));
    }
    /** UI projection adds only the current eligible level, without writing legacy data. */
    @SuppressWarnings("unchecked")
    public Map<String,Object> project(String user, Map<String,Object> legacy) {
        ProgramProgress p = refresh(user);
        Map<String,Object> result = new LinkedHashMap<>(legacy);
        State active = p.subjects.get(p.activeSubject);
        if (active != null) result.put("_current", Map.of("operation", active.subject(), "level", active.currentLevel()));
        User student = users.findById(user).orElseThrow();
        if (continuesFractions(p, student)) result.put("_current", Map.of("operation", "frac", "level", fractionLevel(student)));
        for (State s : p.subjects.values()) {
            if (s.status() != Status.LEARNING) continue;
            Map<String,Object> op = new LinkedHashMap<>((Map<String,Object>) result.getOrDefault(s.subject(), Map.of()));
            int level = s.currentLevel();
            Map<String,Object> node = new LinkedHashMap<>((Map<String,Object>) op.getOrDefault("L"+level,
                com.infinityisland.util.ProgressDefaults.createLevelNode(level, true)));
            node.put("unlocked", true);
            Map<String,Object> white = new LinkedHashMap<>((Map<String,Object>) node.getOrDefault("white", Map.of()));
            white.put("unlocked", true);
            node.put("white", white);
            op.put("L"+level,node); result.put(s.subject(),op);
        }
        return result;
    }
}
