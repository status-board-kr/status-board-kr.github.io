package kr.statusboard.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object FleetPayments {
    const val TEMPLATE = "[{회사}]\n{이름}님, 안녕하세요.\n{차량정보} 장기렌터카 이용료 결제일은 {날짜}입니다.\n\n▪ 결제 계좌: {계좌}\n\n항상 이용해주셔서 감사합니다."
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    fun key(plate: String) = plate.map { if (it in ".#$[]/") '_' else it }.joinToString("")
    fun dueDate(today: LocalDate, payDay: Int): LocalDate {
        require(payDay in 1..31)
        // Browser Date(y,m,day) rolls overflow days into the following month.
        return today.withDayOfMonth(1).plusDays(payDay - 1L)
    }
    fun status(vehicle: Map<String, Any?>, override: Map<String, Any?>, today: LocalDate): String {
        val month = YearMonth.from(today).toString()
        if (override["lastSentMonth"] == month) return if (override["lastSentBy"] == "auto") "자동발송완료" else "발송완료"
        val day = vehicle["payDay"]?.toString()?.toIntOrNull()?.takeIf { it in 1..31 } ?: return "결제일 미등록"
        val delta = ChronoUnit.DAYS.between(today, dueDate(today, day))
        return when { delta == 0L -> "D-DAY"; delta < 0 -> "D+${-delta}"; else -> "D-$delta" }
    }
    fun message(vehicle: Map<String, Any?>, override: Map<String, Any?>, settings: Map<String, Any?>, today: LocalDate): String {
        val custom = map(override["customMessage"])
        if (custom["ym"] == YearMonth.from(today).toString() && !custom["text"]?.toString().isNullOrBlank()) return custom["text"].toString()
        val accounts = map(settings["accounts"])
        val account = map(accounts[override["accountType"] ?: "corp"] ?: accounts["corp"])
        val number = account["number"]?.toString().orEmpty()
        val holder = account["holder"]?.toString().orEmpty()
        val formatted = if (number.isBlank()) "계좌 정보 미등록 (설정에서 등록해주세요)" else
            "${account["bank"] ?: ""} $number${if (holder.isBlank()) "" else " (예금주: $holder)"}".trim()
        val model = vehicle["model"]?.toString().orEmpty(); val plate = vehicle["plate"]?.toString().orEmpty()
        val car = if (model.isNotBlank()) "$model${if (plate.isBlank()) "" else "($plate)"}" else plate.ifBlank { "차량" }
        return settings["template"]?.toString()?.ifBlank { TEMPLATE }.orEmpty().ifBlank { TEMPLATE }
            .replace("{회사}", settings["company"]?.toString().orEmpty())
            .replace("{이름}", vehicle["customerName"]?.toString()?.ifBlank { "고객" } ?: "고객")
            .replace("{차량정보}", car).replace("{계좌}", formatted)
            .replace("{날짜}", vehicle["payDay"]?.let { "${today.monthValue}월 ${it}일" } ?: "미등록")
    }
}
