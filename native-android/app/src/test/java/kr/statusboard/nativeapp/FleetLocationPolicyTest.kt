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
        for ((at, reason) in listOf("2026-10-03T10:00:00+09:00" to FleetLocationReason.WEEKEND, "2026-10-04T10:00:00+09:00" to FleetLocationReason.WEEKEND,
            "2026-10-09T10:00:00+09:00" to FleetLocationReason.HOLIDAY, "2026-10-05T18:00:00+09:00" to FleetLocationReason.OUTSIDE_HOURS))
            assertEquals(FleetLocationDecision(false, false, reason), check(at))
        assertEquals(FleetLocationDecision(false, false, FleetLocationReason.ADMIN), check("2026-10-05T10:00:00+09:00", admin = true))
    }
    @Test fun consentPersistsButRevokedOsPermissionPromptsAgain() {
        for (day in listOf("05", "06")) {
            val at = "2026-10-${day}T09:00:00+09:00"
            assertEquals(FleetLocationDecision(true, false, FleetLocationReason.CONSENT), check(at))
            assertEquals(FleetLocationDecision(false, true, FleetLocationReason.ACTIVE), check(at, consent = true, permission = true))
            assertEquals(FleetLocationDecision(true, false, FleetLocationReason.PERMISSION), check(at, consent = true))
        }
    }
    @Test fun missingCalendarAndInvalidHoursCannotEnableTracking() {
        assertEquals(FleetLocationDecision(false, false, FleetLocationReason.UNKNOWN_CALENDAR), check("2027-10-05T10:00:00+09:00", consent = true, permission = true))
        assertEquals(FleetLocationDecision(false, false, FleetLocationReason.INVALID_HOURS), check("2026-10-05T10:00:00+09:00", start = "09:00", end = "09:00"))
    }
    @Test fun overnightShiftUsesItsStartingWorkday() {
        assertEquals(FleetLocationDecision(false, true, FleetLocationReason.ACTIVE), check("2026-10-06T01:00:00+09:00", true, true, start = "22:00", end = "06:00"))
        assertEquals(FleetLocationDecision(false, false, FleetLocationReason.WEEKEND), check("2026-10-05T01:00:00+09:00", true, true, start = "22:00", end = "06:00"))
    }
}
