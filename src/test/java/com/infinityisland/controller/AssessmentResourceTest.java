package com.infinityisland.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentResourceTest {
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
