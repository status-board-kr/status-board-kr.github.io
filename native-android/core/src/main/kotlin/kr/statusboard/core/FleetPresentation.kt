package kr.statusboard.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

object FleetPresentation {
    val filters = listOf("전체", "대기", "준비중", "보험", "일반", "장기")
    fun matches(vehicle: FleetVehicle, filter: String, longBranch: String): Boolean = when (filter) {
        "전체" -> true
        "대기", "준비중" -> vehicle.status == filter
        "장기" -> vehicle.type == longBranch
        else -> vehicle.type == filter
    }
    fun date(value: String?): LocalDate? = try { value?.take(10)?.let(LocalDate::parse) } catch (_: Exception) { null }
    fun isDue(vehicle: FleetVehicle, today: LocalDate): Boolean = date(vehicle.returnDate)?.let { !it.isAfter(today) } ?: false
    fun groups(vehicles: List<FleetVehicle>, home: String, long: String, today: LocalDate): List<Pair<String, List<FleetVehicle>>> {
        val groups = vehicles.groupBy { it.branch?.takeIf(String::isNotBlank) ?: home }
        val order = (listOf(home, long) + groups.keys).distinct().filter(groups::containsKey)
        // Stable ordering within each group, including equal-priority due vehicles.
        return order.map { branch -> branch to groups.getValue(branch).sortedBy { if (isDue(it, today)) 0 else 1 } }
    }
    fun warnings(vehicle: FleetVehicle, today: LocalDate): List<String> {
        val raw = vehicle.rawFields; val result = mutableListOf<String>()
        fun warning(value: LocalDate?, limit: Long, label: String) {
            if (value != null && ChronoUnit.DAYS.between(today, value) <= limit)
                result += "$label ${if (value.isBefore(today)) "만료" else "만료 임박"} · $value"
        }
        warning(date(raw["insuranceDate"]?.toString()), 30, "보험")
        val asDate = date(raw["regDate"]?.toString())?.plusYears((raw["asYears"] as? Number)?.toLong() ?: raw["asYears"]?.toString()?.toLongOrNull() ?: 3)
        if (raw["asAckExpire"]?.toString() != asDate?.toString()) warning(asDate, 60, "A/S")
        return result
    }
}
