package kr.statusboard.nativeapp

import org.junit.Assert.*
import org.junit.Test
import kr.statusboard.core.FleetSales
import java.time.LocalDate
import java.time.YearMonth

class FleetSalesTest {
    private val old = mapOf("type" to "일반", "plate" to "123가0000", "saleKey" to "test", "amount" to 20.0,
        "startDate" to "2026-09-30", "returnDate" to "2026-10-02")
    @Test fun extensionPreservesInitialAmountAndIsIdempotent() {
        val next = old + mapOf("amount" to 35.0, "returnDate" to "2026-10-04", "depositPaid" to true)
        assertTrue(FleetSales.needsExtensionChoice(old, next))
        val sale = FleetSales.sale(null, old, next, true, "test-edit", "2026-10-02T00:00:00Z", "test", LocalDate.of(2026,10,2))
        val items = sale["items"] as List<*>
        assertEquals(2, items.size)
        assertEquals(20.0, (items[0] as Map<*, *>)["amount"])
        assertEquals(15.0, (items[1] as Map<*, *>)["amount"])
        assertEquals(sale, FleetSales.sale(sale, old, next, true, "test-edit", "later", "test", LocalDate.of(2026,10,3)))
    }
    @Test fun correctionPreservesUnknownSaleFieldsAndChangesLastItem() {
        val previous = mapOf("custom" to "keep", "items" to listOf(mapOf("kind" to "최초", "amount" to 20.0, "date" to "2026-09-30"),
            mapOf("kind" to "연장", "amount" to 15.0, "date" to "2026-10-02")))
        val sale = FleetSales.sale(previous, old, old + ("amount" to 30.0), false, "fix", "2026-10-03T00:00:00Z", "test", LocalDate.of(2026,10,3))
        assertEquals("keep", sale["custom"])
        assertEquals(10.0, ((sale["items"] as List<*>).last() as Map<*, *>)["amount"])
        assertEquals(30.0, sale["amount"])
    }
    @Test fun splitUsesExclusiveReturnDateAtMonthBoundary() {
        val item = mapOf("amount" to 30.0, "date" to "2026-09-30", "to" to "2026-10-03")
        assertEquals(10.0, FleetSales.monthAmount(item, YearMonth.of(2026,9), true), 0.001)
        assertEquals(20.0, FleetSales.monthAmount(item, YearMonth.of(2026,10), true), 0.001)
        assertEquals(30.0, FleetSales.monthAmount(item, YearMonth.of(2026,9), false), 0.001)
    }
}
