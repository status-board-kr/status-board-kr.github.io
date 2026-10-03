package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
class FleetHistoryTest {
    @Test fun restoringSnapshotCannotResurrectOldOutboxAndLosesNoCompletedIds() {
        val current = mapOf("9" to mapOf("plate" to "가1234", "_nativeCompleted" to mapOf("new" to true)))
        val old = listOf(mapOf("plate" to "가1234", "note" to "예전", "_nativeOperations" to mapOf("old" to mapOf("recall" to true)), "_nativeCompleted" to mapOf("older" to true)))
        val result = FleetHistory.restore(current, old)
        val car = result["0"] as Map<*, *>
        assertFalse(car.containsKey("_nativeOperations"))
        assertEquals(setOf("new", "older"), (car["_nativeCompleted"] as Map<*, *>).keys)
    }
    @Test(expected = IllegalStateException::class) fun pendingEffectsBlockRestore() {
        FleetHistory.restore(listOf(mapOf("plate" to "가1234", "_nativeEdits" to mapOf("pending" to true))), emptyList<Any>())
    }
}
