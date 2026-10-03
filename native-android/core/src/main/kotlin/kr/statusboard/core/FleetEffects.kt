package kr.statusboard.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface FleetEffectStore {
    suspend fun mutate(path: String, transform: (Any?) -> Any?): Any?
    suspend fun vehicle(transform: (Any?) -> Any?): Any?
}
object FleetEffects {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    private suspend fun createOnce(store: FleetEffectStore, path: String, value: Map<String, Any?>) { store.mutate(path) { it ?: value } }
    suspend fun finish(store: FleetEffectStore, id: String, op: Map<String, Any?>) {
        val at = op["at"] as? String ?: error("요청 시각이 없습니다.")
        val plate = op["plate"] as? String ?: error("차량번호가 없습니다.")
        require(plate.isNotBlank() && plate.none { it in ".#$[]/" })
        require(id.isNotBlank() && id.none { it in ".#$[]/" })
        val time = Instant.parse(at); val zone = ZoneId.of("Asia/Seoul")
        val recall = op["recall"] == true
        val oldDate = (op["oldReturnDate"] as? String)?.takeIf(String::isNotBlank)
        val nextDate = (op["newReturnDate"] as? String)?.takeIf(String::isNotBlank)
        val schedule = "schedules/${FleetCommands.returnKey(plate)}"
        if (recall) {
            val record = mapOf("title" to "$plate 회수", "plate" to plate, "type" to op["oldType"],
                "date" to (oldDate ?: time.atZone(zone).toLocalDate().toString()), "done" to true, "auto" to true,
                "doneAt" to at, "billed" to false, "nativeOperation" to id, "returnKm" to op["returnKm"],
                "photoId" to op["photoId"], "otherPlates" to op["otherPlates"])
            var archived = false
            if (oldDate != null) {
                val updated = store.mutate(schedule) { current ->
                    val existing = map(current)
                    if (existing["nativeOperation"] == id) current
                    else if (existing["date"] == oldDate && existing["done"] != true)
                        existing + mapOf("done" to true, "doneAt" to at, "type" to op["oldType"], "plate" to plate, "nativeOperation" to id,
                            "returnKm" to op["returnKm"], "photoId" to op["photoId"], "otherPlates" to op["otherPlates"])
                    else current
                }
                archived = map(updated)["nativeOperation"] == id
            }
            if (!archived) createOnce(store, "schedules/$id", record)
            createOnce(store, "wookyJobs/$id", mapOf("plate" to plate, "endAt" to at, "status" to "pending", "at" to at,
                "by" to op["by"], "nativeOperation" to id, "km" to op["returnKm"]))
        } else if (oldDate != null && nextDate == null) {
            store.mutate(schedule) { current ->
                val record = map(current)
                if (record["date"] == oldDate) record + mapOf("done" to true, "doneAt" to at) else current
            }
        }
        if (!recall && nextDate != null) {
            val previous = map(store.mutate(schedule) { it })
            if (previous["done"] == true && previous["date"] != nextDate)
                createOnce(store, "schedules/$id-prior", previous)
            store.mutate(schedule) { current ->
                val record = map(current)
                if (current != null && record["date"] !in listOf(oldDate, nextDate) && record["done"] != true) current
                else if (record["done"] == true && record["date"] == nextDate) current
                else (if (record["done"] == true) emptyMap() else record) + mapOf("date" to nextDate,
                    "title" to "$plate 차량 반납일", "plate" to plate, "type" to op["newType"], "auto" to true,
                    "done" to false, "repeat" to false, "nativeScheduleOperation" to id)
            }
        }
        createOnce(store, "plateHistory/$plate/$id", mapOf("time" to DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(zone).format(time),
            "text" to (if (recall) "회수 처리 → 대기" else op["message"].toString()) + (op["otherPlates"]?.let { " · 상대차량 $it" } ?: ""),
            "nativeOperation" to id, "photoId" to op["photoId"]))
        createOnce(store, "chat/$id-result", mapOf("uid" to "system", "email" to "현황판", "type" to "system", "at" to at,
            "text" to op["message"].toString().replace("종결 요청 처리 중", "종결 요청 저장됨")))
        store.vehicle { current ->
            if (current == null) null else {
                val raw = map(current); val pending = map(raw["_nativeOperations"])
                // The fleet may have been reordered while effects were saving. Do not mark another car.
                if (!pending.containsKey(id)) current else {
                    val done = (map(raw["_nativeCompleted"]) + (id to at)).entries.sortedByDescending { it.value.toString() }.take(100).associate { it.toPair() }
                    raw + mapOf("_nativeOperations" to (pending - id), "_nativeCompleted" to done)
                }
            }
        }
    }
}
