package kr.statusboard.core

import java.time.LocalDate

object FleetVehicleDocuments {
    // Match the original browser's Date.setFullYear leap-day rollover.
    fun addYears(date: LocalDate, years: Int): LocalDate = LocalDate.of(date.year + years, date.monthValue, 1).plusDays(date.dayOfMonth - 1L)
    fun defaults(vehicle: Map<String, Any?>): Map<String, Any?> {
        val registration = FleetPresentation.date(vehicle["regDate"]?.toString()) ?: return emptyMap()
        val years = when (vehicle["cls"]) { "대형" -> 8; "승합" -> 9; else -> 5 }
        val age = FleetPresentation.date(vehicle["ageExpireDate"]?.toString()) ?: addYears(registration, years)
        val inspection = FleetPresentation.date(vehicle["inspectionDate"]?.toString()) ?: addYears(registration, if (vehicle["cls"] == "승합") 1 else 2)
        return mapOf("ageExpireDate" to age.toString(), "insuranceDate" to (vehicle["insuranceDate"] ?: addYears(registration, 1).toString()),
            "inspectionDate" to inspection.toString(), "inspectionType" to if (inspection >= age.minusMonths(2)) "연장" else "일반")
    }
    fun completeInspection(vehicle: Map<String, Any?>): Map<String, Any?> {
        val inspection = FleetPresentation.date(vehicle["inspectionDate"]?.toString()) ?: error("검사일자를 먼저 입력해주세요.")
        var age = FleetPresentation.date(vehicle["ageExpireDate"]?.toString())
        val count = (vehicle["ageExtendCount"] as? Number)?.toInt() ?: vehicle["ageExtendCount"]?.toString()?.toIntOrNull() ?: 0
        val extending = vehicle["inspectionType"] == "연장" && age != null && count < 2
        if (extending) age = addYears(age!!, 1)
        val next = addYears(inspection, 1)
        return mapOf("inspectionDate" to next.toString(), "inspectionDone" to false,
            "inspectionType" to if (age != null && next >= age.minusMonths(2)) "연장" else "일반") +
            if (extending) mapOf("ageExpireDate" to age.toString(), "ageExtendCount" to count + 1) else emptyMap()
    }
    fun renewInsurance(vehicle: Map<String, Any?>): String = addYears(
        FleetPresentation.date(vehicle["insuranceDate"]?.toString()) ?: error("보험 갱신일자를 먼저 입력해주세요."), 1).toString()
    fun warrantyExpiry(vehicle: Map<String, Any?>): String? {
        val date = FleetPresentation.date(vehicle["regDate"]?.toString()) ?: return null
        val years = (vehicle["asYears"] as? Number)?.toInt() ?: vehicle["asYears"]?.toString()?.toIntOrNull() ?: 3
        return addYears(date, years).toString()
    }
}
