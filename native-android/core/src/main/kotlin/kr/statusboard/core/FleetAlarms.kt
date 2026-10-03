package kr.statusboard.core

import java.time.*

data class FleetAlarm(val seed: String, val at: Instant, val title: String, val body: String, val open: String)
object FleetAlarms {
    val zone: ZoneId = ZoneId.of("Asia/Seoul")
    fun workStart(now: Instant, isAdmin: Boolean, consent: Boolean, start: LocalTime, end: LocalTime, holidays: FleetHolidayCalendar): List<FleetAlarm> {
        if (isAdmin || !consent) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        return (0L until 14L).mapNotNull { offset ->
            val date = today.plusDays(offset); val at = date.atTime(start).atZone(zone).toInstant()
            if (at <= now || !FleetLocationPolicy.decide(at, false, true, true, start, end, holidays).collect) null
            else FleetAlarm("locstart-$date-$start", at, "위치 공유 시작 시간이에요", "앱을 한 번 열면 근무시간 동안 위치 공유가 시작됩니다.", "location")
        }
    }
    fun build(vehicles: List<FleetVehicle>, schedules: List<Map<String, Any?>>, now: Instant): List<FleetAlarm> {
        val result = mutableListOf<FleetAlarm>(); val today = now.atZone(zone).toLocalDate()
        val limit = now.plusSeconds(30 * 86400L)
        fun add(seed: String, date: LocalDate, hour: Int, title: String, body: String, open: String) {
            val at = date.atTime(hour, 0).atZone(zone).toInstant()
            if (at > now && at <= limit) result += FleetAlarm(seed, at, title, body, open)
        }
        vehicles.forEach { vehicle -> FleetPresentation.date(vehicle.returnDate)?.let { date ->
            val body = "${vehicle.plate} (${vehicle.model})"
            add("ret1-${vehicle.plate}-$date", date.minusDays(1), 18, "내일 반납 예정", body, "date:$date")
            add("ret0-${vehicle.plate}-$date", date, 9, "오늘 반납일", body, "date:$date")
        } }
        val payments = mutableMapOf<LocalDate, MutableList<FleetVehicle>>()
        vehicles.forEach { vehicle ->
            val day = vehicle.rawFields["payDay"]?.toString()?.toIntOrNull()?.takeIf { it in 1..31 }
            val amount = vehicle.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0
            if (day != null && amount > 0) (0..2).forEach { offset ->
                val date = today.withDayOfMonth(1).plusMonths(offset.toLong()).plusDays(day - 1L)
                payments.getOrPut(date) { mutableListOf() } += vehicle
            }
        }
        payments.forEach { (date, cars) ->
            val sum = cars.sumOf { it.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0 }
            add("pay-$date", date, 9, "오늘 결제일", "${cars.size}대 · ${sum}만원 (${cars.take(3).joinToString { it.plate }})", "payment")
        }
        val cutoff = now.minusSeconds(86400)
        val pending = schedules.filter { record -> record["done"] == true && (record["auto"] == true || record["manual"] == true) && record["billed"] != true && record["type"] != "일반" &&
            runCatching { Instant.parse(record["doneAt"].toString()) <= cutoff }.getOrDefault(false) }
        if (pending.isNotEmpty()) add("unbilled-$today", today.plusDays(1), 9, "청구하지 않은 회수 건", "${pending.size}건이 아직 청구 전입니다 (${pending.take(3).joinToString { it["plate"].toString() }})", "payment")
        return result.sortedBy { it.at }
    }
}
