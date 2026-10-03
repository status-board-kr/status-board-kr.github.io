package kr.statusboard.nativeapp

import org.junit.Assert.*
import org.junit.Test
import kr.statusboard.core.*
import java.time.Instant
import java.time.LocalDate

class FleetWidgetSnapshotTest {
    @Test fun repeatsRespectSelectedDayAndCompletion() {
        val date = LocalDate.of(2026, 10, 3)
        val item = mapOf("date" to "2026-01-03T18:00", "repeat" to true,
            "completedDates" to mapOf("2026-10-03" to false, "2026-11-03" to "done"))
        assertTrue(FleetWidgetSnapshot.matches(item, date))
        assertFalse(FleetWidgetSnapshot.done(item, date))
        assertTrue(FleetWidgetSnapshot.done(item, date.plusMonths(1)))
        assertFalse(FleetWidgetSnapshot.matches(item, date.plusDays(1)))
    }
    @Test fun koreanDateAndTwentyFiveMonthsIncludeNonDuplicateEvents() {
        val event = mapOf("date" to "2026-10-04T09:30", "title" to "테스트 일정", "memo" to "시험용", "done" to false)
        val data = FleetWidgetSnapshot.build(emptyList(), listOf(event, event), "장기", 2, null, Instant.parse("2026-10-03T15:01:00Z"))
        assertEquals("2026-10-04", data["todayStr"])
        val months = data["months"] as Map<*, *>
        assertEquals(25, months.size)
        val calendar = months["2026-10"] as Map<*, *>
        assertEquals(1, (calendar["count"] as Map<*, *>)["4"])
        val first = ((calendar["events"] as Map<*, *>)["4"] as List<*>).first() as Map<*, *>
        assertEquals("09:30", first["time"])
        assertEquals("테스트 일정", first["title"])
    }
}
