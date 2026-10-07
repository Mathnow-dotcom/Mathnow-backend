package com.infinityisland.controller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AssessmentResourceTest {
    @Test void csvQuotesDataAndPreventsSpreadsheetFormulaExecution() {
        assertEquals("\"Ranjan, Student\"", AssessmentAdminResource.csv("Ranjan, Student"));
        assertEquals("\"A \"\"name\"\"\"", AssessmentAdminResource.csv("A \"name\""));
        assertEquals("\"'=1+1\"", AssessmentAdminResource.csv("=1+1"));
        assertEquals("\"'  +1\"", AssessmentAdminResource.csv("  +1"));
        assertEquals("\"3\"", AssessmentAdminResource.csv("3"));
    }
}
