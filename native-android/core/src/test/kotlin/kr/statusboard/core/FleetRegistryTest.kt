package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
class FleetRegistryTest {
    @Test fun sparseOrderAndConcurrentExistingFieldsRemainUnchangedOnRetry() {
        val old = mapOf("2" to mapOf("plate" to "가1234", "note" to "원본", "unknown" to 77), "9" to mapOf("plate" to "나5678"))
        val first = FleetRegistry.append(old, listOf(mapOf("plate" to "다9012", "branch" to "지점")), "same")
        assertEquals(old["2"], first["2"])
        assertTrue(first.containsKey("10"))
        assertEquals(first, FleetRegistry.append(first, listOf(mapOf("plate" to "다9012")), "same"))
    }
    @Test(expected = IllegalStateException::class) fun anotherUsersExistingPlateCannotBeOverwritten() {
        FleetRegistry.append(listOf(mapOf("plate" to "가1234")), listOf(mapOf("plate" to "가1234", "note" to "덮어쓰기")), "new")
    }
}
