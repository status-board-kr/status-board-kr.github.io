package kr.statusboard.nativeapp

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The durable per-vehicle outbox makes a committed recall recoverable after a network failure.
 * Effect ids are stable, so retries cannot reset an already completed Wooky job or count a new run.
 * Only existing company paths are used. Never write a cached whole-company/vehicle array.
 */
class FleetOperations(private val auth: FirebaseAuth, private val transport: FleetTransport) {
    private val db = FirebaseDatabase.getInstance()
    private val lock = Mutex()
    private val zone = ZoneId.of("Asia/Seoul")
    private suspend fun verify(session: FleetSession): FleetSession {
        check(auth.currentUser?.uid == session.uid) { "로그인 계정이 변경되었습니다." }
        val current = FleetAccessResolver(NativeMembership(transport)).resolve(session.uid)
        if (current?.cacheKey != session.cacheKey) throw AccessDenied()
        return current
    }
    private fun root(session: FleetSession) = db.getReference(session.path(""))
    fun newId(session: FleetSession) = root(session).child("chat").push().key!!

    suspend fun sendText(session: FleetSession, id: String, text: String, home: String, long: String,
        photos: List<String> = emptyList(), command: FleetCommand? = null, applyCommands: Boolean = true): String = lock.withLock {
        require((text.isNotBlank() || photos.isNotEmpty()) && text.length <= 2000)
        verify(session)
        val company = root(session)
        val now = Instant.now()
        createOnce(company.child("chat/$id"), mapOf("text" to text.ifBlank { "📷 사진 ${photos.size}장" }, "uid" to session.uid,
            "email" to auth.currentUser?.email.orEmpty(), "at" to now.toString(), "type" to if (photos.isEmpty()) "text" else "photo",
            "photoId" to photos.firstOrNull(), "photoIds" to photos.takeIf { it.size > 1 }))
        if (!applyCommands) return@withLock "사진 전송됨"
        val cmd = command ?: try { FleetCommands.parse(text, long) } catch (error: IllegalArgumentException) {
            val result = error.message.orEmpty(); system(company, id, result, now); return@withLock result
        } ?: return@withLock "전송됨"
        val vehicles = company.child("vehicles").get().await()
        val matches = vehicles.children.filter { it.child("plate").getValue(String::class.java)?.endsWith(cmd.plateToken) == true }
        if (matches.size != 1) {
            val result = if (matches.isEmpty()) "뒷자리 ${cmd.plateToken} 차량을 찾을 수 없습니다." else "같은 뒷자리 차량이 여러 대입니다. 차량 상세에서 선택해주세요."
            system(company, id, result, now); return@withLock result
        }
        val hit = matches.single()
        val plate = hit.child("plate").getValue(String::class.java)!!
        for (pending in hit.child("_nativeOperations").children) {
            if (pending.key != id) finish(company, hit.ref, pending.key!!, asMap(pending.value))
        }
        if (cmd.queryOnly) {
            val result = "$plate (${hit.child("model").value ?: "—"})\n구분: ${hit.child("type").value ?: "—"}\n상태: ${hit.child("status").value ?: "—"}\n메모: ${hit.child("note").value ?: "—"}"
            system(company, id, result, now); return@withLock result
        }
        if (cmd.kmFix != null) return@withLock correctMileage(company, id, plate, cmd.kmFix, now)
        // A restore snapshot is recorded before mutation, using server data rather than local cache.
        createOnce(company.child("history/$id"), mapOf("vehicles" to vehicles.value, "savedAt" to now.toEpochMilli()))
        val ref = company.child("vehicles/${hit.key}")
        val operation = transact(ref) { current ->
            if (current == null) null else {
                val raw = asMap(current)
                check(raw["plate"] == plate) { "차량 순서가 변경됐습니다. 다시 확인해주세요." }
                val completed = asMap(raw["_nativeCompleted"])
                val pending = asMap(raw["_nativeOperations"])
                if (completed.containsKey(id) || pending.containsKey(id)) raw else {
                    val changes = FleetCommands.changes(raw, cmd, home, long, now.atZone(zone).toLocalDate(), now)
                    val next = raw + changes
                    val result = "✓ $plate ${if (cmd.recall) "회수 완료" else if (cmd.dispatch) "배차 완료" else "변경됨"}\n구분: ${next["type"] ?: "—"}\n상태: ${next["status"] ?: "—"}\n메모: ${next["note"] ?: "—"}" +
                        if (cmd.recall) "\n종료 주행거리: ${cmd.returnKm?.let { "${it}km" } ?: "미입력"} · 종결 요청 처리 중" else ""
                    val op = mapOf("at" to now.toString(), "plate" to plate, "recall" to cmd.recall,
                        "oldReturnDate" to raw["returnDate"], "oldType" to raw["type"],
                        "newReturnDate" to next["returnDate"], "newType" to next["type"],
                        "message" to result, "by" to auth.currentUser?.email.orEmpty(), "returnKm" to cmd.returnKm,
                        "photoId" to cmd.photoId, "otherPlates" to cmd.otherPlates.joinToString(" ").ifBlank { null })
                    next + ("_nativeOperations" to (pending + (id to op)))
                }
            }
        } ?: error("차량이 삭제됐습니다. 다시 확인해주세요.")
        val op = asMap(asMap(operation)["_nativeOperations"])[id]
        if (op != null) finish(company, ref, id, asMap(op))
        "현황판에 반영됨"
    }

    /** Called after each authenticated refresh, before admitting new commands. */
    suspend fun recover(session: FleetSession) = lock.withLock {
        verify(session)
        val company = root(session)
        for (vehicle in company.child("vehicles").get().await().children) {
            for (op in vehicle.child("_nativeOperations").children) {
                finish(company, vehicle.ref, op.key!!, asMap(op.value))
            }
        }
    }
    suspend fun saveSchedule(session: FleetSession, key: String?, title: String, date: String, repeat: Boolean, memo: String): String = lock.withLock {
        verify(session)
        require(title.isNotBlank() && title.length <= 200 && memo.length <= 2000)
        LocalDate.parse(date.take(10))
        val company = root(session)
        val id = key ?: company.child("schedules").push().key!!
        require(id.none { it in ".#$[]/" })
        company.child("schedules/$id").updateChildren(mapOf("title" to title.trim(), "date" to date, "repeat" to repeat, "memo" to memo.trim())).await()
        id
    }
    suspend fun deleteSchedule(session: FleetSession, key: String) = lock.withLock {
        verify(session)
        require(key.isNotBlank() && key.none { it in ".#$[]/" })
        root(session).child("schedules/$key").removeValue().await()
    }
    suspend fun saveVehicle(session: FleetSession, original: FleetVehicle, fields: Map<String, Any?>) = lock.withLock {
        verify(session)
        val editable = setOf("type", "startDate", "returnDate", "status", "note", "extra", "depositPaid", "regDate",
            "ageExpireDate", "asYears", "insuranceDate", "inspectionType", "inspectionDate", "amount", "payDay", "customerName", "customerPhone")
        require(fields.keys.all { it in editable })
        for (key in listOf("startDate", "returnDate", "regDate", "ageExpireDate", "insuranceDate", "inspectionDate")) {
            fields[key]?.toString()?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it.take(10)) }
        }
        fields["amount"]?.toString()?.let { require(it.toDoubleOrNull()?.let { value -> value.isFinite() && value >= 0 } == true) { "금액을 확인해주세요." } }
        fields["payDay"]?.toString()?.let { require(it.toIntOrNull()?.let { value -> value in 1..31 } == true) { "결제일은 1~31일입니다." } }
        fields["asYears"]?.toString()?.let { require(it.toIntOrNull()?.let { value -> value in 1..10 } == true) { "A/S 기간은 1~10년입니다." } }
        val changed = fields.filter { (key, value) -> original.rawFields[key] != value }
        if (changed.isEmpty()) return@withLock
        val company = root(session)
        val latest = company.child("vehicles").get().await()
        val hit = latest.children.singleOrNull { it.child("plate").value == original.plate } ?: error("차량을 다시 선택해주세요.")
        val id = company.child("history").push().key!!
        createOnce(company.child("history/$id"), mapOf("vehicles" to latest.value, "savedAt" to Instant.now().toEpochMilli()))
        transact(hit.ref) { value ->
            if (value == null) null else {
                val raw = asMap(value)
                check(raw["plate"] == original.plate) { "차량 순서가 변경됐습니다. 다시 열어주세요." }
                check(changed.keys.all { key -> raw[key]?.toString() == original.rawFields[key]?.toString() }) {
                    "다른 직원이 해당 항목을 수정했습니다. 다시 열어 확인해주세요."
                }
                raw + changed
            }
        }
    }
    suspend fun toggleSchedule(session: FleetSession, key: String, date: String) = lock.withLock {
        verify(session); LocalDate.parse(date)
        require(key.none { it in ".#$[]/" })
        transact(root(session).child("schedules/$key")) { value ->
            check(value != null) { "일정이 삭제되었습니다." }
            val record = asMap(value); val now = Instant.now().toString()
            if (record["repeat"] == true) {
                val done = asMap(record["completedDates"]).toMutableMap()
                if (done[date] != null && done[date] != false) done.remove(date) else done[date] = now
                record + ("completedDates" to done)
            } else record + mapOf("done" to (record["done"] != true), "doneAt" to if (record["done"] == true) null else now)
        }
    }
    suspend fun retryWooky(session: FleetSession): Int = lock.withLock {
        val actual = verify(session)
        check(actual.isAdmin) { "관리자만 종결 요청을 재시도할 수 있습니다." }
        val ref = root(session).child("wookyJobs")
        ref.get().await()
        var count = 0
        transact(ref) { value ->
            count = 0
            val jobs = asMap(value).toMutableMap()
            val occupied = jobs.values.map(::asMap).filter { it["status"] in listOf("pending", "working") }.map { it["plate"] }.toMutableSet()
            jobs.keys.sorted().forEach { id ->
                val job = asMap(jobs[id])
                if (job["status"] == "done" && job["result"] in listOf("fail", "error") && job["plate"] !in occupied) {
                    jobs[id] = job + mapOf("status" to "pending", "result" to null, "resultMsg" to null, "announced" to false,
                        "hint" to null, "diag" to null, "retryCount" to ((job["retryCount"] as? Number)?.toInt().orZero() + 1), "retriedAt" to Instant.now().toString())
                    occupied += job["plate"]; count++
                }
            }
            jobs
        }
        count
    }
    private fun Int?.orZero() = this ?: 0
    private suspend fun finish(company: DatabaseReference, vehicle: DatabaseReference, id: String, op: Map<String, Any?>) {
        FleetEffects.finish(object : FleetEffectStore {
            override suspend fun mutate(path: String, transform: (Any?) -> Any?): Any? = transact(company.child(path), transform)
            override suspend fun vehicle(transform: (Any?) -> Any?): Any? = transact(vehicle, transform)
        }, id, op)
    }
    private suspend fun correctMileage(company: DatabaseReference, id: String, plate: String, km: Long, now: Instant): String {
        val hit = company.child("schedules").get().await().children.filter {
            it.child("plate").value == plate && it.child("done").value == true && (it.child("auto").value == true || it.child("manual").value == true)
        }.maxByOrNull { it.child("doneAt").value?.toString().orEmpty() }
        val message = if (hit == null) "회수 기록이 없습니다. 먼저 회수해주세요." else {
            hit.ref.updateChildren(mapOf("returnKm" to km, "kmFixedAt" to now.toString())).await()
            "$plate 종료 주행거리를 ${km}km로 수정했습니다."
        }
        system(company, id, message, now); return message
    }
    private suspend fun system(company: DatabaseReference, id: String, text: String, now: Instant) =
        createOnce(company.child("chat/$id-result"), mapOf("text" to text, "uid" to "system", "email" to "현황판", "at" to now.toString(), "type" to "system"))
    private suspend fun createOnce(ref: DatabaseReference, payload: Map<String, Any?>) { transact(ref) { it ?: payload } }
    private suspend fun transact(ref: DatabaseReference, transform: (Any?) -> Any?): Any? = suspendCancellableCoroutine { continuation ->
        var failure: Exception? = null
        ref.runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                return try { data.value = transform(data.value); Transaction.success(data) }
                catch (error: Exception) { failure = error; Transaction.abort() }
            }
            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (!continuation.isActive) return
                val problem = failure ?: error?.let { if (it.code == DatabaseError.PERMISSION_DENIED) AccessDenied() else Exception("저장 응답을 받지 못했습니다. 같은 요청으로 다시 시도해주세요.") }
                if (problem != null) continuation.resumeWithException(problem)
                else if (!committed) continuation.resumeWithException(Exception("저장이 취소됐습니다."))
                else continuation.resume(snapshot?.value)
            }
        }, false)
    }
    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
}
