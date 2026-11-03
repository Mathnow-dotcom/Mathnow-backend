package com.infinityisland.controller;

import com.infinityisland.dao.AssessmentAttempt;
import com.infinityisland.service.AssessmentService;
import com.infinityisland.service.GameConfigService;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Path("/admin/assessments")
@Produces(MediaType.APPLICATION_JSON)
public class AssessmentAdminResource {
    @org.springframework.beans.factory.annotation.Autowired private com.infinityisland.service.ProgramService program;
    @GET @Path("progress")
    public Object progress(@HeaderParam("x-pin") String pin, @QueryParam("student") String student) {
        admin(pin);
        return program.report(student);
    }
    private static final DateTimeFormatter CSV_TIMESTAMP = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT)
        .withZone(ZoneId.of("America/Los_Angeles"));

    static String csvTimestamp(Date value) {
        return value == null ? "" : CSV_TIMESTAMP.format(value.toInstant());
    }
    static double averageTimeMs(AssessmentAttempt attempt) {
        return attempt.answers.isEmpty() ? 0 : (double) attempt.totalMs / attempt.answers.size();
    }

    static double correctUnderTwoSecondsPercent(AssessmentAttempt attempt) {
        return correctUnderSecondsPercent(attempt, 2000);
    }
    static double correctUnderSecondsPercent(AssessmentAttempt attempt, long thresholdMs) {
        if (attempt.items.isEmpty()) return 0;
        long qualifying = attempt.answers.stream()
            .filter(answer -> answer.correct() && answer.timeMs() >= 0 && answer.timeMs() < thresholdMs)
            .count();
        return 100.0 * qualifying / attempt.items.size();
    }
    private final AssessmentService tests;
    private final GameConfigService config;
    public AssessmentAdminResource(AssessmentService tests, GameConfigService config) {
        this.tests = tests; this.config = config;
    }
    private void admin(String pin) {
        if (!config.isValidAdminPin(pin)) throw new ForbiddenException("Admin access required");
    }

    @GET
    public List<Map<String, Object>> reports(@HeaderParam("x-pin") String pin, @QueryParam("student") String student,
            @QueryParam("type") String type, @QueryParam("from") String from, @QueryParam("to") String to,
            @DefaultValue("0") @QueryParam("page") int page) {
        admin(pin);
        return tests.reports(tests.reportQuery(student, type, from, to), page).stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id); row.put("student", a.studentName); row.put("pin", a.studentPin);
            row.put("type", a.type); row.put("startedAt", a.startedAt); row.put("completedAt", a.completedAt);
            row.put("status", a.completed ? "Completed" : a.abandoned ? "Ended unfinished" : "In progress");
            row.put("endedAt", a.endedAt); row.put("answered", a.answers.size());
            row.put("correct", a.correctCount); row.put("count", a.items.size());
            row.put("percent", a.items.isEmpty() ? 0 : 100.0 * a.correctCount / a.items.size());
            row.put("totalMs", a.totalMs + a.questionMs); row.put("answers", a.answers);
            row.put("items", a.items.stream().map(item -> item.problem(a.type)).toList());
            row.put("averageTimeMs", averageTimeMs(a));
            row.put("correctUnderTwoSecondsPercent", correctUnderTwoSecondsPercent(a));
            row.put("correctUnderThreeSecondsPercent", correctUnderSecondsPercent(a, 3000));
            row.put("batch", a.programBatch);
            row.put("part", a.legacyPart != null ? a.legacyPart : a.programPart);
            row.put("partBatch", a.programPartBatch);
            row.put("attemptNumber", a.programBatch);
            row.put("passed", a.programPassed);
            row.put("flow", a.programBatch == null ? "legacy" : "learning");
            return row;
        }).toList();
    }

    @GET @Path("export") @Produces("text/csv")
    public Response export(@HeaderParam("x-pin") String pin, @QueryParam("student") String student,
            @QueryParam("type") String type, @QueryParam("from") String from, @QueryParam("to") String to) {
        admin(pin);
        var query = tests.reportQuery(student, type, from, to);
        StreamingOutput output = stream -> {
            var writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
            writer.write("Attempt,Student,PIN,Test,Started America/Los_Angeles,Completed America/Los_Angeles,Correct,Items,Percent,Total ms,Item,Problem,Answer,Correct item,Time ms,Average time per item seconds,Correct under 2 seconds percent,Correct under 3 seconds percent,Answered at America/Los_Angeles,Batch,Passed,Addition part,Part batch,Test flow,Status,Answered items,Ended America/Los_Angeles\r\n");
            try (var attempts = tests.export(query)) {
                var iterator = attempts.iterator();
                while (iterator.hasNext()) {
                    AssessmentAttempt a = iterator.next();
                    double averageSeconds = averageTimeMs(a) / 1000;
                    double fastCorrectPercent = correctUnderTwoSecondsPercent(a);
                    double underThreePercent = correctUnderSecondsPercent(a, 3000);
                    for (int i = 0; i < Math.max(1, a.items.size()); i++) {
                        var answer = i < a.answers.size() ? a.answers.get(i) : null;
                        Object[] values = {a.id, a.studentName, a.studentPin, a.type, csvTimestamp(a.startedAt), csvTimestamp(a.completedAt),
                            a.correctCount, a.items.size(), a.items.isEmpty() ? 0 : 100.0 * a.correctCount / a.items.size(), a.totalMs + a.questionMs,
                            i + 1, i < a.items.size() ? a.items.get(i).problem(a.type) : "", answer == null ? "" : answer.answer(), answer == null ? "" : answer.correct(), answer == null ? "" : answer.timeMs(),
                            averageSeconds, fastCorrectPercent, underThreePercent, answer == null ? "" : csvTimestamp(answer.answeredAt()),
                            a.programBatch, a.programPassed, a.legacyPart != null ? a.legacyPart : a.programPart, a.programPartBatch,
                            a.programBatch == null ? "HIGH STAKES TEST" : "Learning assessment",
                            a.completed ? "Completed" : a.abandoned ? "Ended unfinished" : "In progress", a.answers.size(), csvTimestamp(a.endedAt)};
                        for (int j = 0; j < values.length; j++) {
                            if (j > 0) writer.write(',');
                            writer.write(csv(values[j]));
                        }
                        writer.write("\r\n");
                    }
                }
            }
            writer.flush();
        };
        return Response.ok(output).header("Content-Disposition", "attachment; filename=mathnow-test-results.csv").build();
    }
    static String csv(Object value) {
        String text = Objects.toString(value, "");
        if (text.stripLeading().matches("^[=+@\\-].*")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
