package kr.statusboard.core

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.round

/** Amounts are in 만원, matching the existing generalSales records. */
object FleetSales {
    private fun amount(value: Any?): Double = (value as? Number)?.toDouble() ?: value?.toString()?.toDoubleOrNull() ?: 0.0
    @Suppress("UNCHECKED_CAST") private fun record(value: Any?) = value as? Map<String, Any?> ?: emptyMap()
    fun needsExtensionChoice(old: Map<String, Any?>, next: Map<String, Any?>): Boolean =
        old["type"] == "일반" && next["type"] == "일반" && !old["saleKey"]?.toString().isNullOrBlank() &&
            old["amount"] != null && next["amount"] != null && amount(next["amount"]) > amount(old["amount"])
    fun sale(previous: Map<String, Any?>?, oldVehicle: Map<String, Any?>, vehicle: Map<String, Any?>,
        extend: Boolean, id: String, at: String, by: String, today: LocalDate): Map<String, Any?> {
        val completed = record(previous?.get("_nativeEdits"))
        if (completed.containsKey(id)) return previous!!
        val existing = (previous?.get("items") as? List<*>)?.map { record(it).toMutableMap() }?.takeIf { it.isNotEmpty() }
        val items = existing?.toMutableList() ?: mutableListOf(mutableMapOf<String, Any?>(
            "kind" to "최초", "amount" to amount(previous?.get("amount") ?: if (extend) oldVehicle["amount"] else vehicle["amount"]),
            "date" to (previous?.get("date") ?: vehicle["startDate"] ?: today.toString()),
            "to" to (previous?.get("returnDate") ?: if (extend) oldVehicle["returnDate"] else vehicle["returnDate"])))
        if (extend) {
            require(needsExtensionChoice(oldVehicle, vehicle))
            items += mutableMapOf("kind" to "연장", "amount" to amount(vehicle["amount"]) - amount(oldVehicle["amount"]),
                "date" to (oldVehicle["returnDate"] ?: today.toString()), "to" to vehicle["returnDate"], "at" to at)
        } else {
            val last = items.last(); val others = items.dropLast(1).sumOf { amount(it["amount"]) }
            last["amount"] = (amount(vehicle["amount"]) - others).coerceAtLeast(0.0)
            last["to"] = vehicle["returnDate"] ?: last["to"]
            if (items.size == 1) last["date"] = vehicle["startDate"] ?: last["date"]
        }
        return previous.orEmpty() + mapOf("plate" to vehicle["plate"], "model" to (vehicle["model"] ?: ""),
            "customerName" to (vehicle["customerName"] ?: ""), "amount" to items.sumOf { amount(it["amount"]) },
            "items" to items, "depositPaid" to (vehicle["depositPaid"] == true), "date" to (items.first()["date"] ?: today.toString()),
            "startDate" to vehicle["startDate"], "returnDate" to vehicle["returnDate"], "updatedAt" to at, "by" to by,
            "_nativeEdits" to (completed + (id to at)))
    }
    fun monthAmount(item: Map<String, Any?>, month: YearMonth, split: Boolean): Double {
        val from = FleetPresentation.date(item["date"]?.toString()) ?: return 0.0
        val to = FleetPresentation.date(item["to"]?.toString())
        val value = amount(item["amount"])
        if (!split || to == null || !to.isAfter(from)) return if (YearMonth.from(from) == month) value else 0.0
        val start = maxOf(from, month.atDay(1)); val end = minOf(to, month.plusMonths(1).atDay(1))
        val days = ChronoUnit.DAYS.between(start, end).coerceAtLeast(0)
        return round(value * days / ChronoUnit.DAYS.between(from, to).coerceAtLeast(1) * 10) / 10
    }
}
