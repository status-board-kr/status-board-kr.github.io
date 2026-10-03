package kr.statusboard.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A verified calendar is required before enabling employee GPS collection. */
data class FleetHolidayCalendar(val verifiedYears: Set<Int>, val dates: Set<LocalDate>)
data class FleetLocationDecision(val prompt: Boolean, val collect: Boolean)

object FleetLocationPolicy {
    private val zone = ZoneId.of("Asia/Seoul")
    fun decide(now: Instant, isAdmin: Boolean, consentGranted: Boolean, permissionGranted: Boolean,
               start: LocalTime, end: LocalTime, holidays: FleetHolidayCalendar): FleetLocationDecision {
        val local = now.atZone(zone)
        val date = local.toLocalDate()
        val time = local.toLocalTime()
        // For overnight hours, the working day belongs to the preceding start date.
        val workDate = if (start > end && time < end) date.minusDays(1) else date
        val weekday = workDate.dayOfWeek != DayOfWeek.SATURDAY && workDate.dayOfWeek != DayOfWeek.SUNDAY
        val calendarKnown = workDate.year in holidays.verifiedYears
        val workHours = when {
            start < end -> time >= start && time < end
            start > end -> time >= start || time < end
            else -> false // Invalid/equal bounds must not mean all-day tracking.
        }
        val active = !isAdmin && weekday && calendarKnown && workDate !in holidays.dates && workHours
        return FleetLocationDecision(prompt = active && (!consentGranted || !permissionGranted),
            collect = active && consentGranted && permissionGranted)
    }
}
