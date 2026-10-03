package kr.statusboard.nativeapp
import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test

class FleetPhotoRoutingTest {
    private val vehicles = listOf(FleetVehicle(0,"예시하1234","예시", "대기",null,null,null,null,emptyMap()))
    @Test fun captionInsidePhotoCardExecutesTheSameCommand() {
        val route = FleetPhotoRouting.route("1234 ㅎㅅ", PhotoReading(listOf("상대나5678")), vehicles)
        assertTrue(route.command!!.recall)
        assertEquals(listOf("상대나5678"), route.command.otherPlates)
        assertNull(route.command.returnKm)
    }
    @Test fun fullOpposingPlateWithSameTailIsNeverOurVehicle() {
        assertNull(FleetPhotoRouting.match("상대나1234", vehicles))
        val route = FleetPhotoRouting.route("ㅈㅇ 보험 거래처", PhotoReading(listOf("상대나1234"), confident=true), vehicles)
        assertTrue(route.chooseVehicle); assertEquals(listOf("상대나1234"), route.otherPlates)
    }
    @Test fun uncertainReadingWaitsForVehicleChoiceButNotMileage() {
        val uncertain = PhotoReading(listOf("예시하1234"), 50000, false)
        assertTrue(FleetPhotoRouting.route("회수", uncertain, vehicles).chooseVehicle)
        val selected = FleetPhotoRouting.route("회수", uncertain, vehicles, chosenTail="1234")
        assertTrue(selected.command!!.recall); assertNull(selected.command.returnKm)
        assertEquals(50000L, FleetPhotoRouting.route("회수", uncertain.copy(confident=true), vehicles).command!!.returnKm)
    }
    @Test fun choosesFullPlateWhenTwoFleetCarsHaveTheSameSuffix() {
        val two = vehicles + vehicles.single().copy(sourceIndex=1,plate="다른하1234")
        assertTrue(FleetPhotoRouting.route("1234 회수",PhotoReading(),two).chooseVehicle)
        assertEquals("다른하1234", FleetPhotoRouting.route("회수",PhotoReading(),two,chosenTail="다른하1234").command!!.plateToken)
    }
    @Test fun ordinaryPhotoCaptionDoesNotInvokePaidAi() {
        assertFalse(FleetPhotoRouting.needsAnalysis("차량 사진 보내드립니다"))
        assertFalse(FleetPhotoRouting.needsAnalysis(""))
        assertTrue(FleetPhotoRouting.needsAnalysis("1234ㅎㅅ"))
        assertTrue(FleetPhotoRouting.needsAnalysis("ㅈㅇ 보험 거래처"))
    }
}
