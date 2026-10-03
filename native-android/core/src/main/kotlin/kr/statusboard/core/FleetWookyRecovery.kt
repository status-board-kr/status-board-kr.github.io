package kr.statusboard.core

/** Select one failed attempt per plate; active requests always take precedence. */
object FleetWookyRecovery {
    fun candidates(jobs: Map<String, Map<String, Any?>>): List<String> {
        val occupied = jobs.values.filter { it["status"] in setOf("pending", "working") }
            .mapNotNull { it["plate"] as? String }.toMutableSet()
        return jobs.keys.sorted().filter { id ->
            val job = jobs.getValue(id)
            val plate = job["plate"] as? String
            plate != null && plate.isNotBlank() && failed(job) && occupied.add(plate)
        }
    }

    fun failed(job: Map<String, Any?>) = job["status"] == "done" && job["result"] in setOf("fail", "error")
    fun attempt(job: Map<String, Any?>) = (job["retryCount"] as? Number)?.toInt() ?: 0

    fun retry(current: Map<String, Any?>, expected: Map<String, Any?>, at: String): Map<String, Any?> {
        if (!failed(current) || current["plate"] != expected["plate"] || attempt(current) != attempt(expected)) return current
        return current + mapOf("status" to "pending", "result" to null, "resultMsg" to null,
            "announced" to false, "hint" to null, "diag" to null,
            "retryCount" to (attempt(current) + 1), "retriedAt" to at)
    }
}
