package com.infinityisland.dao;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/** A curriculum snapshot. Never shared directly with the student API. */
@Document("assessmentAttempts")
public class AssessmentAttempt {
    @Id public String id;
    @Version public Long version;
    public String userId, studentName, studentPin, type = "A", beginKey, session;
    public boolean completed;
    public Date startedAt, completedAt, lastTick;
    public int position, correctCount;
    public long totalMs, questionMs;
    public List<Item> items = new ArrayList<>();
    public List<Answer> answers = new ArrayList<>();

    public record Item(int a, int b) {
        public String problem() { return a + " + " + b; }
        public int result() { return Math.addExact(a, b); }
    }
    public record Answer(String problem, String answer, boolean correct, long timeMs) {}
}
