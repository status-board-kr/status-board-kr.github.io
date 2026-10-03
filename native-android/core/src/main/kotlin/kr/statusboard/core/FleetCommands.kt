package kr.statusboard.core

import java.time.Instant
import java.time.LocalDate

data class FleetCommand(
    val plateToken: String, val recall: Boolean = false, val dispatch: Boolean = false,
    val status: String? = null, val type: String? = null, val note: String = "",
    val kmFix: Long? = null, val returnKm: Long? = null, val otherPlates: List<String> = emptyList(),
    val photoId: String? = null
) {
    val queryOnly get() = !recall && !dispatch && status == null && type == null && note.isBlank() && kmFix == null && otherPlates.isEmpty()
}
object FleetCommands {
    private val statuses = mapOf("준비중" to "준비중", "준비" to "준비중", "대기" to "대기", "대기중" to "대기", "운행중" to "운행중", "운행" to "운행중")
    fun parse(text: String, longBranch: String = "장기"): FleetCommand? {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val first = Regex("^(\\d{4})(.*)$").matchEntire(words.firstOrNull().orEmpty()) ?: return null
        val rest = listOf(first.groupValues[2]).filter(String::isNotBlank) + words.drop(1)
        val kmIndex = rest.indexOfFirst { it.lowercase() in listOf("키로", "킬로", "km", "주행거리") }
        if (kmIndex >= 0) {
            val km = rest.getOrNull(kmIndex + 1)?.replace(",", "")?.toLongOrNull()
            require(km != null && km > 0) { "주행거리는 0보다 큰 숫자로 입력해주세요." }
            return FleetCommand(first.groupValues[1], kmFix = km)
        }
        val recall = rest.any { it == "회수" || it == "ㅎㅅ" }
        val dispatch = !recall && rest.any { it in listOf("조완", "ㅈㅇ", "조치완료") }
        val statusToken = rest.firstOrNull { statuses.containsKey(it) }
        val type = rest.firstOrNull { it in listOf("보험", "서비스", "일반", longBranch) }
        val note = rest.filter { it != type && it != statusToken && it !in listOf("회수", "ㅎㅅ", "조완", "ㅈㅇ", "조치완료") }.joinToString(" ")
        return FleetCommand(first.groupValues[1], recall, dispatch, statuses[statusToken], type, note)
    }
    fun select(vehicles: List<FleetVehicle>, token: String): FleetVehicle {
        val matches = vehicles.filter { it.plate.endsWith(token) }
        require(matches.isNotEmpty()) { "뒷자리 $token 차량을 찾을 수 없습니다." }
        require(matches.size == 1) { "뒷자리 $token 차량이 여러 대입니다. 차량 상세에서 선택해주세요." }
        return matches.single()
    }
    /** Only changed fields are returned. Apply them to the current server vehicle inside a transaction. */
    fun changes(previous: Map<String, Any?>, command: FleetCommand, homeBranch: String, longBranch: String, today: LocalDate, now: Instant): Map<String, Any?> {
        require(!command.queryOnly && command.kmFix == null)
        val next = previous.toMutableMap()
        if (command.recall || command.type != null) next["saleKey"] = null
        if (command.recall) {
            next.putAll(mapOf("type" to "", "note" to "차고지", "status" to "대기", "startDate" to null, "amount" to null, "payDay" to null, "returnDate" to null, "dispatchSessionActive" to false))
            if (next["branch"] == longBranch) next["branch"] = homeBranch
        } else {
            command.type?.let {
                next.putAll(mapOf("type" to it, "status" to "운행중", "startDate" to today.toString(), "amount" to null, "payDay" to null, "returnDate" to null))
                if (it == longBranch) next["branch"] = longBranch else if (next["branch"] == longBranch) next["branch"] = homeBranch
            }
            command.status?.let { next["status"] = it; if (it != "운행중") next["startDate"] = null }
            if (command.note.isNotBlank()) next["note"] = command.note
            if (command.status == null && command.type == null && Regex("차고지|입고|들어옴").containsMatchIn(command.note)) {
                next["status"] = "대기"; next["startDate"] = null
            }
        }
        if (command.dispatch) {
            if (previous["dispatchSessionActive"] != true || previous["status"] in listOf("대기", "준비중")) {
                val cutoff = now.minusSeconds(30L * 86400).toString()
                val old = (previous["dispatchRuns"] as? List<*>)?.filterIsInstance<Map<*, *>>()?.filter { (it["at"] as? String).orEmpty() >= cutoff }.orEmpty()
                next["dispatchRuns"] = old + mapOf("date" to today.toString(), "at" to now.toString())
                next["dispatchDate"] = today.toString(); next["dispatchAt"] = now.toString()
            }
            next["dispatchSessionActive"] = true
            if (command.status == null) next["status"] = "운행중"
            if ((next["startDate"] as? String).isNullOrBlank()) next["startDate"] = today.toString()
        }
        val base = next["note"]?.toString().orEmpty()
        val extra = command.otherPlates.distinct().filterNot { base.contains(it) }
        if (extra.isNotEmpty()) next["note"] = (listOf(base).filter(String::isNotBlank) + extra).joinToString(" ")
        return next.filter { (key, value) -> previous[key] != value }
    }
    fun returnKey(plate: String) = "auto-return-" + plate.replace(Regex("[.#$\\[\\]/\\s]"), "_")
}
