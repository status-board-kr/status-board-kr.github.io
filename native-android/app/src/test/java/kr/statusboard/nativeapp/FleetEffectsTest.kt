package kr.statusboard.nativeapp
import kotlinx.coroutines.test.runTest
import kr.statusboard.core.*
import org.junit.Assert.*
import org.junit.Test

class FleetEffectsTest {
    private val id="requestA"
    private val op=mapOf<String,Any?>("plate" to "예시하1234","at" to "2026-10-02T23:00:00Z","recall" to true,"oldType" to "보험","message" to "회수 완료 · 종결 요청 처리 중")
    private class Store(op:Map<String,Any?>):FleetEffectStore {
        val records=mutableMapOf<String,Any?>()
        var car:Any?=mapOf("plate" to "예시하1234", "note" to "보존", "_nativeOperations" to mapOf("requestA" to op))
        var failAt:String?=null
        override suspend fun mutate(path:String,transform:(Any?)->Any?):Any? {
            if(path==failAt) {failAt=null;throw Exception("simulated connection loss")}
            val next=transform(records[path]);if(next==null) records.remove(path) else records[path]=next;return next
        }
        override suspend fun vehicle(transform:(Any?)->Any?):Any? {car=transform(car);return car}
    }
    @Test fun interruptedRecallRecoversWithoutResettingAlreadyClosedContractJob()= runTest {
        val store=Store(op);store.failAt="chat/$id-result"
        try {FleetEffects.finish(store,id,op);fail()}catch(_:Exception) { }
        assertTrue((store.car as Map<*,*>)["_nativeOperations"] is Map<*,*>)
        store.records["wookyJobs/$id"]=mapOf("status" to "done","result" to "ok","resultMsg" to "실제 종결 확인")
        FleetEffects.finish(store,id,op)
        assertEquals("done",(store.records["wookyJobs/$id"] as Map<*,*>)["status"])
        assertEquals("실제 종결 확인",(store.records["wookyJobs/$id"] as Map<*,*>)["resultMsg"])
        assertEquals(1,store.records.keys.count {it.startsWith("schedules/")})
        assertTrue(((store.car as Map<*,*>)["_nativeOperations"] as Map<*,*>).isEmpty())
        assertEquals("2026-10-03",(store.records["schedules/$id"] as Map<*,*>)["date"])
    }
    @Test fun neverCompletesNewRedispatchScheduleOrMarksAReorderedVehicle()= runTest {
        val dated=op+ ("oldReturnDate" to "2026-10-03T17:00")
        val store=Store(dated);val key="schedules/${FleetCommands.returnKey("예시하1234")}"
        store.records[key]=mapOf("date" to "2026-10-06T18:00", "done" to false, "memo" to "다음 대여")
        store.car=mapOf("plate" to "다른하5678", "custom" to "보존")
        FleetEffects.finish(store,id,dated)
        assertEquals(false,(store.records[key] as Map<*,*>)["done"])
        assertEquals("다음 대여",(store.records[key] as Map<*,*>)["memo"])
        assertFalse((store.car as Map<*,*>).containsKey("_nativeCompleted"))
    }
    @Test fun originalReturnSlotCreatesOneRecordAndKeepsBillingFields()= runTest {
        val dated=op+ ("oldReturnDate" to "2026-10-03T17:00")
        val store=Store(dated);val key="schedules/${FleetCommands.returnKey("예시하1234")}"
        store.records[key]=mapOf("date" to "2026-10-03T17:00", "done" to false, "billed" to true, "invoice" to "보존")
        FleetEffects.finish(store,id,dated);FleetEffects.finish(store,id,dated)
        assertEquals(1,store.records.keys.count {it.startsWith("schedules/")})
        assertEquals(true,(store.records[key] as Map<*,*>)["billed"])
        assertEquals("보존",(store.records[key] as Map<*,*>)["invoice"])
    }
}
