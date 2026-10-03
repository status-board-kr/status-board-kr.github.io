package kr.statusboard.nativeapp

import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FleetLocationPolicyTest {
    private val calendar = FleetHolidayCalendar(setOf(2026), setOf(LocalDate.parse("2026-10-09")))
    private fun check(at: String, consent: Boolean = false, permission: Boolean = false, admin: Boolean = false,
                      start: String = "09:00", end: String = "18:00", holidays: FleetHolidayCalendar = calendar) =
        FleetLocationPolicy.decide(OffsetDateTime.parse(at).toInstant(), admin, consent, permission,
            LocalTime.parse(start), LocalTime.parse(end), holidays)

    @Test fun weekendsHolidaysEndOfDayAndAdminsAreExempt() {
        for (at in listOf("2026-10-03T10:00:00+09:00", "2026-10-04T10:00:00+09:00",
            "2026-10-09T10:00:00+09:00", "2026-10-05T18:00:00+09:00"))
            assertEquals(FleetLocationDecision(false, false), check(at))
        assertEquals(FleetLocationDecision(false, false), check("2026-10-05T10:00:00+09:00", admin = true))
    }
    @Test fun consentPersistsButRevokedOsPermissionPromptsAgain() {
        for (day in listOf("05", "06")) {
            val at = "2026-10-${day}T09:00:00+09:00"
            assertEquals(FleetLocationDecision(true, false), check(at))
            assertEquals(FleetLocationDecision(false, true), check(at, consent = true, permission = true))
            assertEquals(FleetLocationDecision(true, false), check(at, consent = true))
        }
    }
    @Test fun missingCalendarAndInvalidHoursCannotEnableTracking() {
        assertEquals(FleetLocationDecision(false, false), check("2027-10-05T10:00:00+09:00", consent = true, permission = true))
        assertEquals(FleetLocationDecision(false, false), check("2026-10-05T10:00:00+09:00", start = "09:00", end = "09:00"))
    }
    @Test fun overnightShiftUsesItsStartingWorkday() {
        assertEquals(FleetLocationDecision(false, true), check("2026-10-06T01:00:00+09:00", true, true, start = "22:00", end = "06:00"))
        assertEquals(FleetLocationDecision(false, false), check("2026-10-05T01:00:00+09:00", true, true, start = "22:00", end = "06:00"))
    }
}
