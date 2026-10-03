package kr.statusboard.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

object FleetDocumentCalculation {
    fun number(text: String?): Double = text.orEmpty().replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
    fun won(number: Double): String = String.format(java.util.Locale.US, "%,d", floor(number + .5).toLong())
    fun rentalDays(start: String, end: String, inclusive: Boolean): Long {
        val days = ChronoUnit.DAYS.between(LocalDate.parse(start), LocalDate.parse(end))
        require(days >= 0) { "종료일을 확인해주세요." }
        return if (inclusive) days + 1 else maxOf(1, days)
    }
    fun contractEnd(start: String, months: Int): String {
        require(months > 0)
        val date = LocalDate.parse(start)
        // Same overflow behavior as Date.setMonth in the original app.
        return date.withDayOfMonth(1).plusMonths(months.toLong()).plusDays(date.dayOfMonth - 2L).toString()
    }
    fun discounted(price: Double, units: Double, percent: Double, simple: Boolean): Double {
        require(price.isFinite() && units.isFinite() && percent.isFinite() && price >= 0 && units >= 0 && percent in 0.0..100.0)
        val subtotal = price * units
        return if (simple) subtotal - floor(subtotal * percent / 100 + .5) else floor(subtotal * (1 - percent / 100) + .5)
    }
    fun newcarCost(car: Double, period: Double, deposit: Double, prepay: Double, age21: Boolean, rates: Map<String, Any?>): Double {
        fun value(key: String) = rates[key]?.toString()?.toDoubleOrNull() ?: 0.0
        require(car > 0 && period > 0 && deposit in 0.0..100.0 && prepay in 0.0..100.0)
        val interest = value("rate") / 100 / 12; val months = value("months").takeIf { it > 0 } ?: 60.0
        val principal = car * (1 - value("down") / 100)
        val installment = if (interest > 0) principal * interest / (1 - (1 + interest).pow(-months)) else principal / months
        val upfront = car * (value("down") + value("acq")) / 100 + value("reg")
        val base = installment + upfront / period + listOf("ins", "maint", "fee", "etc").sumOf(::value)
        return maxOf(0.0, base - car * deposit / 100 * interest - car * prepay / 100 / period + if (age21) value("age21") else 0.0)
    }
    fun newcarPrice(cost: Double, margin: Double): Double = maxOf(0.0, floor((cost + margin) / 1000 + .5) * 1000)
    fun maskIdentity(text: String) = text.replace(Regex("(?<!\\d)(\\d{6})\\s*-?\\s*([1-8])\\d{6}(?!\\d)"), "$1-$2******")
}
