package kr.statusboard.core

import java.time.Instant
import java.time.ZoneId

/** Recovers manual vehicle edits without adding an extension twice or dropping a ledger record. */
object FleetEditEffects {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    suspend fun finish(store: FleetEffectStore, id: String, op: Map<String, Any?>) {
        val old = map(op["old"]); val next = map(op["next"])
        val plate = next["plate"]?.toString() ?: error("차량번호가 없습니다.")
        val at = op["at"]?.toString() ?: error("수정 시각이 없습니다.")
        val today = Instant.parse(at).atZone(ZoneId.of("Asia/Seoul")).toLocalDate()
        fun key(value: Any?): String? = value?.toString()?.takeIf(String::isNotBlank)?.also { require(it.none { ch -> ch in ".#$[]/" }) }
        val saleKey = key(next["saleKey"]); val oldKey = key(old["saleKey"])
        if (next["type"] == "일반" && saleKey != null) {
            store.mutate("generalSales/$saleKey") { previous ->
                FleetSales.sale(previous?.let(::map), old, next, op["extend"] == true, id, at, op["by"]?.toString().orEmpty(), today)
            }
        } else if (next["type"] == "일반" && oldKey != null && ((next["amount"] as? Number)?.toDouble() ?: 0.0) == 0.0) {
            store.mutate("generalSales/$oldKey") { current ->
                // Do not delete a record another operation subsequently modified.
                if (map(current)["updatedAt"]?.toString()?.let { it > at } == true) current else null
            }
        }
        val oldDate = old["returnDate"]; val date = next["returnDate"]
        val path = "schedules/${FleetCommands.returnKey(plate)}"
        if (date != null || oldDate != null) {
            val previous = map(store.mutate(path) { it })
            if (previous["done"] == true && date != null && date != previous["date"])
                store.mutate("schedules/$id-prior") { it ?: previous }
            store.mutate(path) { value ->
                val record = map(value)
                when {
                    record["_nativeEdit"] == id -> value
                    value != null && record["date"] !in listOf(oldDate, date) -> value
                    date == null -> record + mapOf("done" to true, "doneAt" to at, "plate" to plate,
                        "type" to next["type"], "depositPaid" to next["depositPaid"], "_nativeEdit" to id)
                    date == oldDate -> record + mapOf("type" to next["type"], "depositPaid" to next["depositPaid"], "_nativeEdit" to id)
                    else -> (if (record["done"] == true) emptyMap() else record) + mapOf("title" to "$plate 차량 반납일", "date" to date, "repeat" to false,
                        "auto" to true, "plate" to plate, "type" to next["type"], "depositPaid" to next["depositPaid"],
                        "done" to false, "doneAt" to null, "_nativeEdit" to id)
                }
            }
        }
        store.vehicle { value ->
            if (value == null) null else {
                val raw = map(value); val pending = map(raw["_nativeEdits"])
                if (raw["plate"] != plate || !pending.containsKey(id)) value else {
                    val done = (map(raw["_nativeCompletedEdits"]) + (id to at)).entries
                        .sortedByDescending { it.value.toString() }.take(100).associate { it.toPair() }
                    raw + mapOf("_nativeEdits" to (pending - id), "_nativeCompletedEdits" to done)
                }
            }
        }
    }
}
