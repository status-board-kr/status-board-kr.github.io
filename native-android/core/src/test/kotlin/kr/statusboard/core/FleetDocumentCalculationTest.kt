package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
class FleetDocumentCalculationTest {
    @Test fun inclusiveRentalAndMonthlyOverflowFollowExistingForms() {
        assertEquals(2L, FleetDocumentCalculation.rentalDays("2026-10-11", "2026-10-12", true))
        assertEquals(1L, FleetDocumentCalculation.rentalDays("2026-10-11", "2026-10-12", false))
        assertEquals("2026-03-02", FleetDocumentCalculation.contractEnd("2026-01-31", 1))
        assertEquals(90000.0, FleetDocumentCalculation.discounted(50000.0, 2.0, 10.0, true), .001)
    }
    @Test fun zeroInterestCalculationAndIdentityMask() {
        val cost = FleetDocumentCalculation.newcarCost(30000000.0, 36.0, 0.0, 10.0, false, mapOf("rate" to 0, "months" to 60, "ins" to 100000))
        assertEquals(516666.666666, cost, .01)
        assertEquals("예시 900101-1******", FleetDocumentCalculation.maskIdentity("예시 900101-1234567"))
        assertEquals("900101-1******", FleetDocumentCalculation.maskIdentity("9001011234567"))
    }
}
