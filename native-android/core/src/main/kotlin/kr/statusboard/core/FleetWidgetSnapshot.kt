package kr.statusboard.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The native widgets consume the same schema as the approved production widgets. */
object FleetWidgetSnapshot {
    fun matches(item: Map<String, Any?>, date: LocalDate): Boolean {
        val source = FleetPresentation.date(item["date"]?.toString()) ?: return false
        return if (item["repeat"] == true) source.dayOfMonth == date.dayOfMonth else source == date
    }
    fun done(item: Map<String, Any?>, date: LocalDate): Boolean {
        val value = if (item["repeat"] == true) (item["completedDates"] as? Map<*, *>)?.get(date.toString()) else item["done"]
        return value != null && value != false && value != "" && value != 0 && value != 0L
    }
    fun build(vehicles: List<FleetVehicle>, schedules: List<Map<String, Any?>>, longBranch: String,
        unread: Int, health: Map<String, Any?>?, now: Instant): Map<String, Any?> {
        val local = now.atZone(ZoneId.of("Asia/Seoul")); val today = local.toLocalDate()
        val byPlate = vehicles.associateBy { it.plate }
        fun items(date: LocalDate) = schedules.filter { matches(it, date) && !done(it, date) }
            .distinctBy { if (it["auto"] == true && !it["plate"].toString().isBlank()) "r:${it["plate"]}" else "t:${it["title"]}" }
        fun line(item: Map<String, Any?>): String = if (item["auto"] == true && item["plate"] != null) {
            val plate = item["plate"].toString(); val customer = byPlate[plate]?.rawFields?.get("customerName")?.toString().orEmpty()
            "🚗 ${plate.takeLast(4)} 반납" + if (customer.isBlank()) "" else " · $customer"
        } else "📅 ${item["title"]?.toString()?.take(18) ?: "일정"}"
        val months = (-12L..12L).associate { offset ->
            val month = YearMonth.from(today).plusMonths(offset)
            val days = (1..month.lengthOfMonth()).associateWith { items(month.atDay(it)) }.filterValues { it.isNotEmpty() }
            month.toString() to mapOf("y" to month.year, "m" to month.monthValue,
                "today" to if (YearMonth.from(today) == month) today.dayOfMonth else 0,
                "firstDow" to month.atDay(1).dayOfWeek.value % 7, "days" to month.lengthOfMonth(),
                "count" to days.mapKeys { it.key.toString() }.mapValues { it.value.size },
                "items" to days.mapKeys { it.key.toString() }.mapValues { it.value.take(8).map(::line) },
                "events" to days.mapKeys { it.key.toString() }.mapValues { (_, values) -> values.take(8).map { item ->
                    val vehicle = byPlate[item["plate"]?.toString()]
                    val time = item["time"]?.toString() ?: item["date"]?.toString()?.drop(11)?.take(5).orEmpty()
                    mapOf("time" to time.takeIf { Regex("\\d{2}:\\d{2}").matches(it) }.orEmpty(),
                        "title" to if (item["auto"] == true && item["plate"] != null) item["plate"] else item["title"] ?: "일정",
                        "note" to item["memo"]?.toString()?.takeIf(String::isNotBlank) ?: vehicle?.note ?: vehicle?.rawFields?.get("customerName") ?: "",
                        "kind" to if (item["auto"] == true) "회수" else "일정")
                } })
        }
        val idle = vehicles.count { it.status == "대기" }; val prep = vehicles.count { it.status == "준비중" }
        val long = vehicles.count { it.type == longBranch }
        val lines = items(today).map(::line)
        return mapOf("total" to vehicles.size, "idle" to idle, "prep" to prep, "run" to vehicles.size - idle - prep - long,
            "insurance" to vehicles.count { it.type == "보험" }, "general" to vehicles.count { it.type == "일반" }, "long" to long,
            "unread" to unread.coerceAtLeast(0), "todayStr" to today.toString(), "today" to lines,
            "soon" to (lines.map { "오늘 $it" } + items(today.plusDays(1)).map { "내일 ${line(it)}" }),
            "updated" to local.format(DateTimeFormatter.ofPattern("M/d HH:mm")) + " 갱신", "updatedAt" to now.toString(),
            "agentHealth" to health, "cal" to months[YearMonth.from(today).toString()], "months" to months)
    }
}
