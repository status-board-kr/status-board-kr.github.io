package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class FleetAlarmsTest {
    @Test fun changedReturnAndCompletedBillingRemoveTheirPreviousReminders() {
        val now = Instant.parse("2026-10-03T00:00:00Z")
        val vehicle = FleetVehicle(9, "예시1234", "K5", "운행중", "일반", "지점", null, "2026-10-05", emptyMap())
        val recall = mapOf<String, Any?>("done" to true, "auto" to true, "type" to "보험", "plate" to "예시5678", "doneAt" to "2026-10-01T00:00:00Z")
        val before = FleetAlarms.build(listOf(vehicle), listOf(recall), now)
        assertEquals(3, before.size)
        assertEquals(Instant.parse("2026-10-04T09:00:00Z"), before.first { it.seed.startsWith("ret1") }.at)
        val after = FleetAlarms.build(listOf(vehicle.copy(returnDate = null)), listOf(recall + ("billed" to true)), now)
        assertTrue(after.isEmpty())
    }
}
