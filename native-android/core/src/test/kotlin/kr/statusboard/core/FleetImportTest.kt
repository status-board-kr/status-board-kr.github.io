package kr.statusboard.core
import org.junit.Assert.*
import org.junit.Test
class FleetImportTest {
    @Test fun quotedNewlinesAndSparseHeaderKeepColumns() {
        val rows = FleetImport.csv("차량번호,비고,차종,연료,종별\r\n99가1234,\"줄1\n줄2,\"\"인용\"\"\",가상모델,경유,중형\r\n99가1234,,중복,경유,중형")
        assertEquals("줄1\n줄2,\"인용\"", rows[1][1])
        val vehicles = FleetImport.vehicles(rows)
        assertEquals(1, vehicles.size)
        assertEquals("가상모델", vehicles.single()["model"])
        assertEquals("디젤", vehicles.single()["fuel"])
    }
    @Test(expected = IllegalArgumentException::class) fun malformedQuotesFailBeforeAnyWrites() { FleetImport.csv("번호,\"끝없는 입력") }
    @Test fun noHeaderAndAdjacentDigitsAreHandled() {
        assertNull(FleetImport.plate("9999가12345"))
        assertEquals("99가1234", FleetImport.vehicles(listOf(listOf("99가1234", "가상차종", "중형", "LPG"))).single()["plate"])
    }
}
