package kr.statusboard.core

import org.junit.Assert.*
import org.junit.Test

class FleetWookyRecoveryTest {
    private fun job(plate: String, status: String = "done", result: String = "fail", attempt: Int = 0) =
        mapOf<String, Any?>("plate" to plate, "status" to status, "result" to result, "retryCount" to attempt, "contractId" to "keep")

    @Test fun activeAndSuccessfulJobsAreNeverRetriedAndDuplicatePlatesSelectOneAttempt() {
        val jobs = mapOf("a" to job("예시1234"), "b" to job("예시1234"),
            "c" to job("예시5678"), "d" to job("예시5678", "working"), "e" to job("예시9999", result = "ok"))
        assertEquals(listOf("a"), FleetWookyRecovery.candidates(jobs))
    }

    @Test fun completionOrConcurrentRetryCannotBeResetByStaleScreen() {
        val previous = job("예시1234")
        val retried = FleetWookyRecovery.retry(previous, previous, "now")
        assertEquals("pending", retried["status"])
        assertEquals("keep", retried["contractId"])
        assertEquals(1, retried["retryCount"])
        val completed = job("예시1234", result = "ok", attempt = 1)
        assertEquals(completed, FleetWookyRecovery.retry(completed, previous, "later"))
        val failedAgain = job("예시1234", attempt = 1)
        assertEquals(failedAgain, FleetWookyRecovery.retry(failedAgain, previous, "later"))
    }
}
