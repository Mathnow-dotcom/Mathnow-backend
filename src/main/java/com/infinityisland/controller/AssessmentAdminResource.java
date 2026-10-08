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
    private static final DateTimeFormatter CSV_TIMESTAMP = DateTimeFormatter
        .ofPattern("uuuu-MM-dd HH:mm:ss XXX", Locale.ROOT)
        .withZone(ZoneId.of("America/Los_Angeles"));

    static String csvTimestamp(Date value) {
        return CSV_TIMESTAMP.format(value.toInstant());
    }
    static double averageTimeMs(AssessmentAttempt attempt) {
        return attempt.items.isEmpty() ? 0 : (double) attempt.totalMs / attempt.items.size();
    }

    static double correctUnderTwoSecondsPercent(AssessmentAttempt attempt) {
        if (attempt.items.isEmpty()) return 0;
        long qualifying = attempt.answers.stream()
            .filter(answer -> answer.correct() && answer.timeMs() >= 0 && answer.timeMs() < 2000)
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
            row.put("correct", a.correctCount); row.put("count", a.items.size());
            row.put("percent", 100.0 * a.correctCount / a.items.size());
            row.put("totalMs", a.totalMs); row.put("answers", a.answers);
            row.put("averageTimeMs", averageTimeMs(a));
            row.put("correctUnderTwoSecondsPercent", correctUnderTwoSecondsPercent(a));
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
            writer.write("Attempt,Student,PIN,Test,Started America/Los_Angeles,Completed America/Los_Angeles,Correct,Items,Percent,Total ms,Item,Problem,Answer,Correct item,Time ms,Average time per item seconds,Correct under 2 seconds percent\r\n");
            try (var attempts = tests.export(query)) {
                var iterator = attempts.iterator();
                while (iterator.hasNext()) {
                    AssessmentAttempt a = iterator.next();
                    double averageSeconds = averageTimeMs(a) / 1000;
                    double fastCorrectPercent = correctUnderTwoSecondsPercent(a);
                    for (int i = 0; i < a.answers.size(); i++) {
                        var answer = a.answers.get(i);
                        Object[] values = {a.id, a.studentName, a.studentPin, a.type, csvTimestamp(a.startedAt), csvTimestamp(a.completedAt),
                            a.correctCount, a.items.size(), 100.0 * a.correctCount / a.items.size(), a.totalMs,
                            i + 1, answer.problem(), answer.answer(), answer.correct(), answer.timeMs(),
                            averageSeconds, fastCorrectPercent};
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
