package com.infinityisland.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentResourceTest {
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
