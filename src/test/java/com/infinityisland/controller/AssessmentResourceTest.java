package com.infinityisland.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentResourceTest {
    @Test void unfinishedAttemptsIncludeUnansweredItemsInReportAndCsv() throws Exception {
        var service = org.mockito.Mockito.mock(com.infinityisland.service.AssessmentService.class);
        var config = org.mockito.Mockito.mock(com.infinityisland.service.GameConfigService.class);
        org.mockito.Mockito.when(config.isValidAdminPin("admin")).thenReturn(true);
        var resource = new AssessmentAdminResource(service, config);
        var a = new com.infinityisland.dao.AssessmentAttempt();
        a.id = "ended"; a.abandoned = true; a.totalMs = 1200;
        a.items.add(new com.infinityisland.dao.AssessmentAttempt.Item(1, 1));
        a.items.add(new com.infinityisland.dao.AssessmentAttempt.Item(2, 2));
        a.answers.add(new com.infinityisland.dao.AssessmentAttempt.Answer("1 + 1", "2", true, 1200));
        org.mockito.Mockito.when(service.reports(null, 0)).thenReturn(java.util.List.of(a));
        var row = resource.reports("admin", null, null, null, null, 0).get(0);
        assertEquals("Ended unfinished", row.get("status"));
        assertEquals(1, row.get("answered"));
        assertEquals(java.util.List.of("1 + 1", "2 + 2"), row.get("items"));
        assertEquals(1200.0, row.get("averageTimeMs"));
        org.mockito.Mockito.when(service.export(null)).thenAnswer(i -> java.util.stream.Stream.of(a));
        var stream = new java.io.ByteArrayOutputStream();
        ((jakarta.ws.rs.core.StreamingOutput) resource.export("admin", null, null, null, null).getEntity()).write(stream);
        var csv = stream.toString(java.nio.charset.StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("HIGH STAKES TEST"));
        org.junit.jupiter.api.Assertions.assertTrue(csv.contains("\"2 + 2\",\"\",\"\",\"\""));
        assertEquals(3, csv.lines().count());
        a.answers.clear(); a.totalMs = 0;
        stream.reset();
        ((jakarta.ws.rs.core.StreamingOutput) resource.export("admin", null, null, null, null).getEntity()).write(stream);
        assertEquals(3, stream.toString(java.nio.charset.StandardCharsets.UTF_8).lines().count());
    }
    @Test void threeSecondMetricExcludesBoundaryAndIncorrectAnswers() {
        var a = new com.infinityisland.dao.AssessmentAttempt();
        for (int i = 0; i < 4; i++) a.items.add(new com.infinityisland.dao.AssessmentAttempt.Item(i, 1));
        a.answers = java.util.List.of(
            new com.infinityisland.dao.AssessmentAttempt.Answer("0 + 1", "1", true, 2999),
            new com.infinityisland.dao.AssessmentAttempt.Answer("1 + 1", "2", true, 3000),
            new com.infinityisland.dao.AssessmentAttempt.Answer("2 + 1", "0", false, 1000),
            new com.infinityisland.dao.AssessmentAttempt.Answer("3 + 1", "4", true, 2000));
        assertEquals(50.0, AssessmentAdminResource.correctUnderSecondsPercent(a, 3000));
        assertEquals("", AssessmentAdminResource.csvTimestamp(null));
    }
    @Test void reportMetricsUseAllItemsAndStrictTwoSecondThreshold() {
        var attempt = new com.infinityisland.dao.AssessmentAttempt();
        assertEquals(0.0, AssessmentAdminResource.averageTimeMs(attempt));
        assertEquals(0.0, AssessmentAdminResource.correctUnderTwoSecondsPercent(attempt));
        for (int i = 0; i < 4; i++) attempt.items.add(new com.infinityisland.dao.AssessmentAttempt.Item(i, 1));
        attempt.totalMs = 8000;
        attempt.answers = java.util.List.of(
            new com.infinityisland.dao.AssessmentAttempt.Answer("0 + 1", "1", true, 1999),
            new com.infinityisland.dao.AssessmentAttempt.Answer("1 + 1", "2", true, 2000),
            new com.infinityisland.dao.AssessmentAttempt.Answer("2 + 1", "0", false, 1000),
            new com.infinityisland.dao.AssessmentAttempt.Answer("3 + 1", "4", true, 3001));
        assertEquals(2000.0, AssessmentAdminResource.averageTimeMs(attempt));
        assertEquals(25.0, AssessmentAdminResource.correctUnderTwoSecondsPercent(attempt));
    }
    @Test void csvTimestampsUseLosAngelesAndObserveDaylightSaving() {
        assertEquals("2026-10-07 07:00:00 -07:00", AssessmentAdminResource.csvTimestamp(
            java.util.Date.from(java.time.Instant.parse("2026-10-07T14:00:00Z"))));
        assertEquals("2026-01-06 23:00:00 -08:00", AssessmentAdminResource.csvTimestamp(
            java.util.Date.from(java.time.Instant.parse("2026-01-07T07:00:00Z"))));
    }
    @Test void csvQuotesDataAndPreventsSpreadsheetFormulaExecution() {
        assertEquals("\"Ranjan, Student\"", AssessmentAdminResource.csv("Ranjan, Student"));
        assertEquals("\"A \"\"name\"\"\"", AssessmentAdminResource.csv("A \"name\""));
        assertEquals("\"'=1+1\"", AssessmentAdminResource.csv("=1+1"));
        assertEquals("\"'  +1\"", AssessmentAdminResource.csv("  +1"));
        assertEquals("\"3\"", AssessmentAdminResource.csv("3"));
    }
}
