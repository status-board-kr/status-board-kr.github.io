package kr.statusboard.core

object FleetRegistry {
    @Suppress("UNCHECKED_CAST") fun indexed(value: Any?): Map<String, Any?> = when (value) {
        null -> emptyMap()
        is List<*> -> value.mapIndexedNotNull { index, item -> item?.let { index.toString() to it } }.toMap()
        is Map<*, *> -> value as Map<String, Any?>
        else -> error("차량 자료 형식을 확인해주세요.")
    }
    fun append(value: Any?, additions: List<Map<String, Any?>>, id: String): Map<String, Any?> {
        require(additions.isNotEmpty() && additions.size <= 200)
        val records = indexed(value).toMutableMap()
        val byPlate = records.values.mapNotNull { (it as? Map<*, *>)?.get("plate")?.toString() }.toMutableSet()
        var index = (records.keys.mapNotNull(String::toIntOrNull).maxOrNull() ?: -1) + 1
        val batchPlates = mutableSetOf<String>()
        additions.forEach { vehicle ->
            val plate = vehicle["plate"]?.toString()?.trim().orEmpty()
            require(plate.isNotBlank() && plate.length <= 20 && batchPlates.add(plate)) { "차량번호가 비어 있거나 중복됩니다." }
            val existing = records.values.mapNotNull { it as? Map<*, *> }.firstOrNull { it["plate"] == plate }
            if (existing != null) {
                check(existing["_nativeRegistration"] == id) { "이미 등록된 차량번호입니다: $plate" }
            } else {
                val fresh = mapOf("no" to "+", "plate" to plate, "type" to "", "status" to "", "note" to "", "inspectionDone" to false,
                    "asYears" to 3, "ageExtendCount" to 0) + vehicle + ("_nativeRegistration" to id)
                records[(index++).toString()] = fresh
                byPlate += plate
            }
        }
        return records
    }
}
