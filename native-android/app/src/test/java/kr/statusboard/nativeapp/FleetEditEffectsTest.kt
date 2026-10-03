package kr.statusboard.nativeapp

import kotlinx.coroutines.test.runTest
import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test

class FleetEditEffectsTest {
    private val old = mapOf("plate" to "123가0000", "type" to "일반", "amount" to 20.0, "saleKey" to "sale-test",
        "startDate" to "2026-10-01", "returnDate" to "2026-10-03")
    private val next = old + mapOf("amount" to 30.0, "returnDate" to "2026-10-05", "depositPaid" to true)
    private val op = mapOf("old" to old, "next" to next, "extend" to true, "at" to "2026-10-03T02:00:00Z", "by" to "test")
    private class Store(op: Map<String, Any?>): FleetEffectStore {
        val records = mutableMapOf<String, Any?>()
        var car: Any? = mapOf("plate" to "123가0000", "custom" to "keep", "_nativeEdits" to mapOf("edit" to op))
        var fail: String? = null
        override suspend fun mutate(path: String, transform: (Any?) -> Any?): Any? {
            if (path == fail) { fail = null; error("network interruption") }
            val value = transform(records[path]); if (value == null) records.remove(path) else records[path] = value
            return value
        }
        override suspend fun vehicle(transform: (Any?) -> Any?): Any? { car = transform(car); return car }
    }
    @Test fun interruptedEditDoesNotDuplicateExtensionAndKeepsNewerSchedule() = runTest {
        val store = Store(op); val path = "schedules/${FleetCommands.returnKey("123가0000")}"
        store.fail = path
        try { FleetEditEffects.finish(store, "edit", op); fail() } catch (_: IllegalStateException) { }
        store.records[path] = mapOf("date" to "2026-10-08", "done" to false, "memo" to "newer rental")
        FleetEditEffects.finish(store, "edit", op)
        val sale = store.records["generalSales/sale-test"] as Map<*, *>
        assertEquals(2, (sale["items"] as List<*>).size)
        assertEquals(30.0, sale["amount"])
        assertEquals("2026-10-08", (store.records[path] as Map<*, *>)["date"])
        assertEquals("keep", (store.car as Map<*, *>)["custom"])
        assertTrue(((store.car as Map<*, *>)["_nativeEdits"] as Map<*, *>).isEmpty())
    }
    @Test fun typeChangeRetainsPreviousSaleAndDoesNotCreateNewOne() = runTest {
        val changed = op + ("next" to (next + mapOf("type" to "보험", "saleKey" to null)))
        val store = Store(changed); val sale = mapOf("amount" to 20.0, "depositPaid" to true)
        store.records["generalSales/sale-test"] = sale
        FleetEditEffects.finish(store, "edit", changed)
        assertEquals(sale, store.records["generalSales/sale-test"])
    }
}
