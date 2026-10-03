package kr.statusboard.nativeapp

import android.content.Context
import kr.statusboard.core.FleetDocumentCalculation as Calc
import org.json.JSONObject
import java.time.LocalDate

object FleetDocumentSchema {
    fun load(context: Context) = JSONObject(context.assets.open("documents-schema.json").bufferedReader().use { it.readText() })
    fun defaults(tab: JSONObject): Map<String, String> = tab.getJSONArray("fields").let { list -> (0 until list.length()).associate { index ->
        val field = list.getJSONObject(index); val id = field.getString("id")
        id to if (id.endsWith("_date")) LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString() else field.optString("value")
    } }
    fun calculated(type: String, fields: Map<String, String>, changed: String, schema: JSONObject, rates: Map<String, Any?>): Map<String, String> {
        val next = fields.toMutableMap()
        fun value(key: String) = next[key].orEmpty()
        fun number(key: String) = Calc.number(value(key))
        val prefix = when (type) { "simple" -> "s"; "quote" -> "q"; "contract" -> "c"; "receipt" -> "r"; "newcar" -> "n"; else -> "st" }
        if (type in setOf("quote", "contract")) {
            if (changed in listOf("${prefix}_grade", "${prefix}_age", "${prefix}_mileage_limit", "${prefix}_own", "${prefix}_maint")) {
                val grade = value("${prefix}_grade"); val age = value("${prefix}_age")
                val base = schema.getJSONObject("priceTable").optJSONObject(grade)?.optDouble(age, 0.0) ?: 0.0
                next["${prefix}_price"] = if (base > 0) Calc.won(base + number("${prefix}_mileage_limit") * 50000 + number("${prefix}_own") * 50000 + number("${prefix}_maint")) else ""
            }
            if (changed in listOf("${prefix}_start", "${prefix}_period") && value("${prefix}_start").isNotBlank() && number("${prefix}_period") > 0) {
                next["${prefix}_end"] = Calc.contractEnd(value("${prefix}_start"), number("${prefix}_period").toInt())
                if (prefix == "c") next["c_payday"] = "매월 ${LocalDate.parse(value("c_start")).dayOfMonth}일"
            }
            if (number("${prefix}_price") > 0 && number("${prefix}_period") > 0)
                next["${prefix}_total"] = Calc.won(Calc.discounted(number("${prefix}_price"), number("${prefix}_period"), number("${prefix}_discount"), false)) + "원"
        }
        if (type in setOf("simple", "receipt") && value("${prefix}_start").isNotBlank() && value("${prefix}_end").isNotBlank()) {
            val days = Calc.rentalDays(value("${prefix}_start"), value("${prefix}_end"), value("${prefix}_unit") == "day")
            next["${prefix}_period"] = "${days}일 (${value("${prefix}_start")} ~ ${value("${prefix}_end")})"
            if (type == "simple" && number("s_price") > 0) {
                next["s_formula"] = "${Calc.won(number("s_price"))}원 × ${days}일"
                next["s_total"] = Calc.won(Calc.discounted(number("s_price"), days.toDouble(), number("s_discount"), true)) + "원"
            }
        }
        if (type == "newcar" && number("n_carprice") > 0 && number("n_period") > 0) {
            for (option in 1..3) {
                val key = "n_p${option}_"
                if (changed == key + "price") next[key + "manual"] = if (value(key + "price").isBlank()) "" else "1"
                if (option > 1 && value(key + "deposit").isBlank() && value(key + "prepay").isBlank()) {
                    next[key + "price"] = ""; continue
                }
                if (changed != key + "price" && value(key + "manual") != "1") {
                    val cost = Calc.newcarCost(number("n_carprice"), number("n_period"), number(key + "deposit"), number(key + "prepay"), value("n_age") == "21", rates)
                    next[key + "price"] = Calc.won(Calc.newcarPrice(cost, rates["margin"]?.toString()?.toDoubleOrNull() ?: 0.0))
                }
            }
        }
        return next
    }
}
