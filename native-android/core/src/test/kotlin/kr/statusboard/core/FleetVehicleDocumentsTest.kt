package kr.statusboard.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FleetVehicleDocumentsTest {
    @Test fun extensionAdvancesInspectionAndAgeOnlyTwice() {
        val first = mapOf<String, Any?>("inspectionDate" to "2026-10-03", "ageExpireDate" to "2026-11-03", "inspectionType" to "연장", "ageExtendCount" to 1, "note" to "보존")
        val changes = FleetVehicleDocuments.completeInspection(first)
        assertEquals("2027-10-03", changes["inspectionDate"])
        assertEquals("2027-11-03", changes["ageExpireDate"])
        assertEquals(2, changes["ageExtendCount"])
        assertFalse(changes.containsKey("note"))
        val second = FleetVehicleDocuments.completeInspection(first + changes)
        assertFalse(second.containsKey("ageExpireDate"))
        assertEquals(false, second["inspectionDone"])
    }
    @Test fun leapYearMatchesExistingWebDates() {
        assertEquals(LocalDate.parse("2025-03-01"), FleetVehicleDocuments.addYears(LocalDate.parse("2024-02-29"), 1))
    }
}
