package kr.statusboard.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FleetQuickIdleTest {
    @Test fun retryCannotResetCompletedCloseJobAndIdleCarDoesNotCreateOne() = runBlocking {
        val values = mutableMapOf<String, Any?>()
        val store = object : FleetEffectStore {
            override suspend fun mutate(path: String, transform: (Any?) -> Any?): Any? = transform(values[path]).also { values[path] = it }
            override suspend fun vehicle(transform: (Any?) -> Any?): Any? = transform(null)
        }
        val previous = mapOf("plate" to "예시1234", "type" to "보험", "status" to "운행중")
        val next = previous + mapOf("type" to "", "status" to "대기")
        val op = mapOf("old" to previous, "next" to next, "quickIdle" to true, "at" to "2026-10-02T01:00:00Z", "by" to "example@example.invalid")
        FleetEditEffects.finish(store, "example", op)
        assertNotNull(values["wookyJobs/edit-example"])
        values["wookyJobs/edit-example"] = mapOf("plate" to "예시1234", "status" to "done", "result" to "success")
        FleetEditEffects.finish(store, "example", op)
        assertEquals("done", (values["wookyJobs/edit-example"] as Map<*, *>)["status"])
        FleetEditEffects.finish(store, "idle", op + ("old" to next))
        assertFalse(values.containsKey("wookyJobs/edit-idle"))
    }
}
