package kr.statusboard.core

object FleetHistory {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    fun restore(current: Any?, previous: Any?): Map<String, Any?> {
        val live = FleetRegistry.indexed(current)
        check(live.values.none { map(it)["_nativeOperations"].let(::map).isNotEmpty() || map(it)["_nativeEdits"].let(::map).isNotEmpty() }) {
            "아직 처리 중인 요청이 있습니다. 완료 후 다시 복원해주세요."
        }
        val byPlate = live.values.map(::map).associateBy { it["plate"] }
        return FleetRegistry.indexed(previous).mapValues { (_, value) ->
            val old = map(value); val existing = byPlate[old["plate"]].orEmpty()
            val result = old.filterKeys { it !in setOf("_nativeOperations", "_nativeEdits") }.toMutableMap()
            for (key in listOf("_nativeCompleted", "_nativeCompletedEdits")) {
                val completed = map(old[key]) + map(existing[key])
                if (completed.isNotEmpty()) result[key] = completed
            }
            existing["_nativeRegistration"]?.let { result["_nativeRegistration"] = it }
            result
        }
    }
}
