package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FleetPaymentsTest {
    @Test fun monthOverrideDoesNotLeakIntoNextMonth() {
        val vehicle = mapOf<String, Any?>("plate" to "가1234", "model" to "K5", "customerName" to "예시", "payDay" to 31)
        val override = mapOf<String, Any?>("customMessage" to mapOf("ym" to "2026-02", "text" to "이번달"), "lastSentMonth" to "2026-02")
        assertEquals("이번달", FleetPayments.message(vehicle, override, emptyMap(), LocalDate.parse("2026-02-28")))
        assertTrue(FleetPayments.message(vehicle, override, emptyMap(), LocalDate.parse("2026-03-01")).contains("3월 31일"))
        assertEquals(LocalDate.parse("2026-03-03"), FleetPayments.dueDate(LocalDate.parse("2026-02-28"), 31))
        assertEquals("D-30", FleetPayments.status(vehicle, override, LocalDate.parse("2026-03-01")))
    }
}
