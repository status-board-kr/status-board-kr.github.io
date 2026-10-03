package kr.statusboard.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class FleetLocationPolicyTest {
    private val calendar = FleetHolidayCalendar(setOf(2026), setOf(LocalDate.parse("2026-10-09")))
    private fun decision(date: String, time: String, admin: Boolean = false, consent: Boolean = true,
                         permission: Boolean = true, start: String = "09:00", end: String = "18:00") =
        FleetLocationPolicy.decide(LocalDateTime.parse("${date}T${time}:00").atZone(ZoneId.of("Asia/Seoul")).toInstant(),
            admin, consent, permission, LocalTime.parse(start), LocalTime.parse(end), calendar)
    @Test fun weekdayBoundariesAndExclusions() {
        assertFalse(decision("2026-10-02", "08:59").collect)
        assertTrue(decision("2026-10-02", "09:00").collect)
        assertTrue(decision("2026-10-02", "17:59").collect)
        assertEquals(FleetLocationReason.OUTSIDE_HOURS, decision("2026-10-02", "18:00").reason)
        assertEquals(FleetLocationReason.WEEKEND, decision("2026-10-03", "10:00").reason)
        assertEquals(FleetLocationReason.HOLIDAY, decision("2026-10-09", "10:00").reason)
        assertFalse(decision("2026-10-09", "10:00", consent = false).prompt)
    }
    @Test fun consentPermissionAndAdminMustNotBeConfusedWithGpsFailure() {
        val refused = decision("2026-10-02", "10:00", consent = false)
        assertTrue(refused.prompt); assertFalse(refused.collect); assertEquals(FleetLocationReason.CONSENT, refused.reason)
        val denied = decision("2026-10-02", "10:00", permission = false)
        assertTrue(denied.prompt); assertFalse(denied.collect); assertEquals(FleetLocationReason.PERMISSION, denied.reason)
        val owner = decision("2026-10-02", "10:00", admin = true, consent = false, permission = false)
        assertFalse(owner.prompt); assertFalse(owner.collect); assertEquals(FleetLocationReason.ADMIN, owner.reason)
    }
    @Test fun invalidHoursAndUnverifiedYearsFailClosed() {
        assertEquals(FleetLocationReason.INVALID_HOURS, decision("2026-10-02", "10:00", start = "09:00", end = "09:00").reason)
        assertEquals(FleetLocationReason.UNKNOWN_CALENDAR, decision("2027-10-01", "10:00").reason)
        assertFalse(decision("2027-10-01", "10:00").collect)
    }
    @Test fun overnightShiftUsesStartingWorkday() {
        assertTrue(decision("2026-10-03", "01:59", start = "22:00", end = "02:00").collect)
        assertFalse(decision("2026-10-03", "02:00", start = "22:00", end = "02:00").collect)
        assertFalse(decision("2026-10-10", "01:00", start = "22:00", end = "02:00").collect)
    }
}
