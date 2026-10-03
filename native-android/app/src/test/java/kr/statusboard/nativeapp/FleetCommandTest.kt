package kr.statusboard.nativeapp

import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class FleetCommandTest {
    private val today = LocalDate.parse("2026-10-03")
    private val now = Instant.parse("2026-10-03T02:00:00Z")
    private val vehicle = mapOf<String, Any?>("plate" to "예시1234", "status" to "대기", "branch" to "본점", "custom" to "보존")
    private fun apply(v: Map<String, Any?>, text: String) = v + FleetCommands.changes(v, FleetCommands.parse(text)!!, "본점", "장기", today, now)
    @Test fun dispatchAliasesCountOnceUntilRecallAndRedispatch() {
        val first = apply(vehicle, "1234 조완 보험 고객")
        val duplicate = apply(first, "1234 ㅈㅇ 보험 고객")
        assertEquals(1, (duplicate["dispatchRuns"] as List<*>).size)
        val recalled = apply(duplicate, "1234 ㅎㅅ")
        val next = apply(recalled, "1234ㅈㅇ 일반 다른고객")
        assertEquals(2, (next["dispatchRuns"] as List<*>).size)
        assertEquals("보존", next["custom"])
    }
    @Test fun recallWithoutMileageClearsRentalAndReturnsLongBranchHome() {
        val previous = vehicle + mapOf("type" to "장기", "branch" to "장기", "returnDate" to "2026-10-03", "amount" to 100, "saleKey" to "saved-sale")
        val next = apply(previous, "1234 회수")
        assertEquals("본점", next["branch"]); assertEquals("대기", next["status"])
        assertNull(next["returnDate"]); assertNull(next["amount"]); assertNull(next["saleKey"])
        assertFalse(next.containsKey("입고자"))
    }
    @Test fun opposingPlateIsAppendedEvenForQueryOrRecall() {
        val command = FleetCommands.parse("1234 회수")!!.copy(otherPlates = listOf("대상5678", "대상5678"))
        val changes = FleetCommands.changes(vehicle, command, "본점", "장기", today, now)
        assertEquals("차고지 대상5678", changes["note"])
        assertFalse(FleetCommands.parse("1234")!!.copy(otherPlates=listOf("대상5678")).queryOnly)
    }
    @Test fun ambiguousSuffixNeverModifiesFirstMatch() {
        val one = FleetVehicle(0,"차량1234","", "대기",null,null,null,null,emptyMap())
        try { FleetCommands.select(listOf(one, one.copy(sourceIndex=1,plate="다른1234")), "1234"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun mileageCorrectionRejectsMissingOrInvalidNumber() {
        assertEquals(37960L, FleetCommands.parse("1234 키로 37,960")!!.kmFix)
        for (text in listOf("1234 키로", "1234 km -1", "1234 키로 abc")) {
            try { FleetCommands.parse(text); fail(text) } catch (_: IllegalArgumentException) { }
        }
    }
}
