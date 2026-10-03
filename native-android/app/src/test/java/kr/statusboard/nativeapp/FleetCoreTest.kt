package kr.statusboard.nativeapp

import kotlinx.coroutines.test.runTest
import kr.statusboard.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FleetCoreTest {
    private fun vehicle(index: Int, plate: String, branch: String? = null, returnDate: String? = null) =
        FleetVehicle(index, plate, "예시", "대기", null, branch, null, returnDate, mapOf("plate" to plate))
    @Test fun preservesManualOrderAndMovesDueStably() {
        val input = listOf(vehicle(0,"C","본점"), vehicle(1,"B","본점","2026-10-02"), vehicle(2,"A","본점","2026-10-01"),vehicle(3,"D","장기"),vehicle(4,"E","다른지점"))
        val groups = FleetPresentation.groups(input,"본점","장기",LocalDate.parse("2026-10-03"))
        assertEquals(listOf("본점","장기","다른지점"),groups.map { it.first })
        assertEquals(listOf("B","A","C"),groups.first().second.map { it.plate })
        assertEquals(listOf("C","B","A","D","E"),input.map { it.plate })
    }
    @Test fun arrayAndSparseFirebaseObjectsKeepSourceIndicesAndUnknownFields() {
        val raw = JSONObject().put("2",JSONObject().put("plate","B").put("dispatchRuns",JSONArray().put(JSONObject().put("date","2026-10-03"))))
            .put("0",JSONObject().put("plate","A").put("customFutureField","preserve"))
        val decoded = VehicleCodec.decode(raw)
        assertEquals(listOf(0,2),decoded.map { it.sourceIndex })
        val restored = VehicleCodec.cached(VehicleCodec.encode(FleetSnapshot(decoded,123)))
        assertEquals(listOf(0,2),restored.vehicles.map { it.sourceIndex })
        assertEquals("preserve",restored.vehicles[0].rawFields["customFutureField"])
        assertEquals("2026-10-03",(restored.vehicles[1].rawFields["dispatchRuns"] as JSONArray).getJSONObject(0).getString("date"))
    }
    @Test fun keepsExpiryAcknowledgementsAndThresholds() {
        val today = LocalDate.parse("2026-10-03")
        val v=vehicle(0,"A").copy(rawFields=mapOf("regDate" to "2023-11-24","asYears" to 3,"insuranceDate" to "2026-10-30"))
        assertEquals(2,FleetPresentation.warnings(v,today).size)
        assertEquals(1,FleetPresentation.warnings(v.copy(rawFields=v.rawFields+ ("asAckExpire" to "2026-11-24")),today).size)
        assertTrue(FleetPresentation.warnings(v.copy(rawFields=mapOf("insuranceDate" to "2026-12-01")),today).isEmpty())
    }
    @Test fun noCompanyAccessWithoutCurrentMembership() = runTest {
        var rolesRead=0
        val gateway=object:MembershipGateway {
            override suspend fun companyFor(uid:String)="companyA"
            override suspend fun roleFor(companyId:String,uid:String):String? {rolesRead++;return null}
        }
        assertNull(FleetAccessResolver(gateway).resolve("userA"));assertEquals(1,rolesRead)
        assertNotEquals(FleetSession("userA","companyA","staff").cacheKey,FleetSession("userA","companyB","staff").cacheKey)
        assertNotEquals(FleetSession("userA","companyA","staff").cacheKey,FleetSession("userB","companyA","staff").cacheKey)
    }
    @Test fun cachedDataReadOnlyThenServerAndRevocationPurgesCache() = runTest {
        val session=FleetSession("user","company","staff"); val snapshot=FleetSnapshot(listOf(vehicle(0,"A")),1)
        var removed=false
        val cache=object:FleetCache {
            override suspend fun read(key:String)=snapshot
            override suspend fun write(key:String,snapshot:FleetSnapshot) { }
            override suspend fun remove(key:String) {removed=true}
        }
        var deny=false
        val gateway=object:FleetGateway {
            override suspend fun readVehicles(session:FleetSession):FleetSnapshot {if(deny)throw AccessDenied();return snapshot.copy(capturedAt=2)}
            override fun isAccessDenied(error:Exception)=error is AccessDenied
        }
        val events=mutableListOf<FleetLoad>();val repo=FleetRepository(cache,gateway)
        repo.load(session) {events+=it}
        assertEquals(listOf(false,true),events.map {it.editable});assertEquals(2L,events.last().snapshot.capturedAt)
        deny=true
        try {repo.load(session) { };fail("Denied access must not succeed")}catch(_:AccessDenied) { }
        assertTrue(removed)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnsafeCompanyPaths() { FleetSession("user","other/company","owner") }
}
