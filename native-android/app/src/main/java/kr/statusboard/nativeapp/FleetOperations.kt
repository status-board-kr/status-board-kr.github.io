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
    suspend fun setMemberName(session: FleetSession, uid: String, name: String) = lock.withLock {
        val current = verify(session)
        require(uid.isNotBlank() && uid.none { it in ".#$[]/" })
        check(current.isAdmin || uid == current.uid) { "본인 이름만 변경할 수 있습니다." }
        val ref = root(session).child("members/$uid")
        transact(ref) { value ->
            check(value != null) { "직원이 이미 삭제되었습니다." }
            asMap(value) + ("name" to name.trim().take(20))
        }
    }
    suspend fun setMemberRole(session: FleetSession, uid: String, role: String) = lock.withLock {
        val current = verify(session)
        check(current.isAdmin && uid != current.uid) { "관리자는 다른 직원의 권한만 변경할 수 있습니다." }
        require(uid.isNotBlank() && uid.none { it in ".#$[]/" } && role in setOf("owner", "staff"))
        transact(root(session).child("members/$uid")) { value ->
            check(value != null) { "직원이 이미 삭제되었습니다." }
            asMap(value) + ("role" to role)
        }
    }
    suspend fun removeMember(session: FleetSession, uid: String) = lock.withLock {
        val current = verify(session)
        check(current.isAdmin && uid != current.uid) { "본인 계정은 내보낼 수 없습니다." }
        require(uid.isNotBlank() && uid.none { it in ".#$[]/" })
        // Membership is the authoritative access control. Do not write another user's userIndex.
        root(session).child("members/$uid").removeValue().await()
        root(session).child("locations/$uid").removeValue().await()
    }
    suspend fun createInvite(session: FleetSession): String = lock.withLock {
        check(verify(session).isAdmin) { "관리자만 초대할 수 있습니다." }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; val random = java.security.SecureRandom()
        repeat(5) {
            val code = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            var created = false
            transact(db.getReference("inviteIndex/$code")) { value ->
                created = value == null
                value ?: mapOf("companyId" to session.companyId, "createdAt" to Instant.now().toString())
            }
            if (created) return@withLock code
        }
        error("초대코드를 만들지 못했습니다. 다시 시도해주세요.")
    }
    suspend fun olderChat(session: FleetSession, before: String?): org.json.JSONObject {
        verify(session)
        val query = root(session).child("chat").orderByKey()
        val page = (if (before == null) query else query.endBefore(before)).limitToLast(50).get().await()
        return org.json.JSONObject(asMap(page.value))
    }
    suspend fun deleteChat(session: FleetSession, id: String) = lock.withLock {
        val current = verify(session)
        require(id.isNotBlank() && id.none { it in ".#$[]/" })
        val ref = root(session).child("chat/$id")
        val message = ref.get().await()
        check(current.isAdmin || message.child("uid").value == current.uid) { "본인 메시지만 삭제할 수 있습니다." }
        ref.removeValue().await()
    }
    private fun validKey(key: String) { require(key.isNotBlank() && key.none { it in ".#$[]/" }) }
    suspend fun savePaymentSettings(session: FleetSession, settings: Map<String, Any?>) = lock.withLock {
        check(verify(session).isAdmin) { "관리자만 결제 설정을 변경할 수 있습니다." }
        require(settings.keys.all { it in setOf("company", "template", "accounts") })
        require(settings["template"].toString().length <= 8000)
        root(session).child("paymentSettings").updateChildren(settings).await()
    }
    suspend fun savePaymentOverride(session: FleetSession, plate: String, fields: Map<String, Any?>) = lock.withLock {
        verify(session)
        require(fields.keys.all { it in setOf("accountType", "customMessage") })
        if (fields.containsKey("accountType")) require(fields["accountType"] in setOf("corp", "personal"))
        root(session).child("paymentOverrides/${FleetPayments.key(plate)}").updateChildren(fields).await()
    }
    suspend fun markPaymentSent(session: FleetSession, plate: String, month: String, message: String, sent: Boolean) = lock.withLock {
        verify(session); java.time.YearMonth.parse(month); require(message.length <= 8000)
        val company = root(session); val key = FleetPayments.key(plate); validKey(key)
        val vehicle = company.child("vehicles").get().await().children.singleOrNull { it.child("plate").value == plate }
            ?: error("차량을 다시 선택해주세요.")
        // One atomic, narrow multi-path write; stable record id makes retry idempotent.
        val updates = mutableMapOf<String, Any?>("paymentOverrides/$key/lastSentMonth" to if (sent) month else null,
            "paymentOverrides/$key/lastSentBy" to if (sent) "manual" else null)
        val id = "native-$key-$month"
        if (sent) updates["paymentSendLog/$id"] = mapOf("plate" to plate, "name" to vehicle.child("customerName").value,
            "ym" to month, "sentAt" to Instant.now().toString(), "message" to message, "sentBy" to "manual", "success" to true)
        else {
            val matching = company.child("paymentSendLog").get().await().children.filter {
                it.child("plate").value == plate && it.child("ym").value == month && it.child("success").value != false
            }
            matching.forEach { updates["paymentSendLog/${it.key}"] = null }
        }
        company.updateChildren(updates).await()
    }
    suspend fun markBilled(session: FleetSession, key: String, billed: Boolean) = lock.withLock {
        verify(session); validKey(key)
        transact(root(session).child("schedules/$key")) { value ->
            check(value != null) { "회수 기록이 삭제되었습니다." }
            val record = asMap(value)
            check(record["done"] == true && (record["auto"] == true || record["manual"] == true)) { "완료된 회수 기록만 청구 처리할 수 있습니다." }
            record + mapOf("billed" to billed, "billedAt" to if (billed) Instant.now().toString() else null)
        }
    }
    suspend fun setSalePaid(session: FleetSession, key: String, paid: Boolean) = lock.withLock {
        verify(session); validKey(key)
        val company = root(session)
        transact(company.child("generalSales/$key")) { value ->
            check(value != null) { "매출 기록이 삭제되었습니다." }
            asMap(value) + mapOf("depositPaid" to paid, "updatedAt" to Instant.now().toString())
        }
        company.child("vehicles").get().await().children.filter { it.child("saleKey").value == key }.forEach { vehicle ->
            transact(vehicle.ref) { value ->
                val raw = asMap(value)
                if (raw["saleKey"] == key && raw["type"] == "일반") raw + ("depositPaid" to paid) else value
            }
        }
    }
    suspend fun deletePaymentLog(session: FleetSession, key: String) = lock.withLock {
        verify(session); validKey(key); root(session).child("paymentSendLog/$key").removeValue().await()
    }

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
        for (pending in hit.child("_nativeEdits").children) finishEdit(company, hit.ref, pending.key!!, asMap(pending.value))
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
            for (op in vehicle.child("_nativeEdits").children) finishEdit(company, vehicle.ref, op.key!!, asMap(op.value))
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
    suspend fun saveVehicle(session: FleetSession, original: FleetVehicle, fields: Map<String, Any?>, id: String, extend: Boolean, home: String, long: String) = lock.withLock {
        verify(session)
        val editable = setOf("type", "startDate", "returnDate", "status", "note", "extra", "depositPaid", "regDate",
            "ageExpireDate", "ageExtendCount", "asYears", "asAckExpire", "insuranceDate", "inspectionType", "inspectionDate", "inspectionDone", "amount", "payDay", "customerName", "customerPhone")
        require(fields.keys.all { it in editable })
        for (key in listOf("startDate", "returnDate", "regDate", "ageExpireDate", "insuranceDate", "inspectionDate")) {
            fields[key]?.toString()?.takeIf(String::isNotBlank)?.let { LocalDate.parse(it.take(10)) }
        }
        fields["amount"]?.toString()?.let { require(it.toDoubleOrNull()?.let { value -> value.isFinite() && value >= 0 } == true) { "금액을 확인해주세요." } }
        fields["payDay"]?.toString()?.let { require(it.toIntOrNull()?.let { value -> value in 1..31 } == true) { "결제일은 1~31일입니다." } }
        fields["asYears"]?.toString()?.let { require(it.toIntOrNull()?.let { value -> value in 1..10 } == true) { "A/S 기간은 1~10년입니다." } }
        val normalized = fields.mapValues { (key, value) -> when {
            value == null -> null
            key in setOf("asYears", "payDay", "ageExtendCount") -> value.toString().toLong()
            key == "amount" -> value.toString().toDouble()
            else -> value
        } }
        val changed = normalized.filter { (key, value) -> original.rawFields[key] != value }
        if (changed.isEmpty()) return@withLock
        val company = root(session)
        val latest = company.child("vehicles").get().await()
        val hit = latest.children.singleOrNull { it.child("plate").value == original.plate } ?: error("차량을 다시 선택해주세요.")
        for (pending in hit.child("_nativeEdits").children) finishEdit(company, hit.ref, pending.key!!, asMap(pending.value))
        createOnce(company.child("history/$id"), mapOf("vehicles" to latest.value, "savedAt" to Instant.now().toEpochMilli()))
        val at = Instant.now(); val committed = transact(hit.ref) { value ->
            if (value == null) null else {
                val raw = asMap(value)
                check(raw["plate"] == original.plate) { "차량 순서가 변경됐습니다. 다시 열어주세요." }
                if (asMap(raw["_nativeCompletedEdits"]).containsKey(id) || asMap(raw["_nativeEdits"]).containsKey(id)) return@transact raw
                check(changed.keys.all { key -> raw[key]?.toString() == original.rawFields[key]?.toString() }) {
                    "다른 직원이 해당 항목을 수정했습니다. 다시 열어 확인해주세요."
                }
                val next = (raw + changed).toMutableMap()
                if (next["type"] == long) next["branch"] = long else if (next["branch"] == long) next["branch"] = home
                if (next["inspectionDate"] != raw["inspectionDate"]) next["inspectionDone"] = false
                if (next["type"] != "일반") next["depositPaid"] = null
                val amount = (next["amount"] as? Number)?.toDouble() ?: 0.0
                if (next["type"] == "일반" && amount > 0) {
                    if (next["saleKey"]?.toString().isNullOrBlank()) next["saleKey"] = "native-$id"
                } else next["saleKey"] = null
                if (extend) check(FleetSales.needsExtensionChoice(raw, next)) { "연장 대상 금액이 변경됐습니다. 다시 확인해주세요." }
                val op = mapOf("old" to raw.filterKeys { !it.startsWith("_native") }, "next" to next.filterKeys { !it.startsWith("_native") },
                    "extend" to extend, "at" to at.toString(), "by" to auth.currentUser?.email.orEmpty())
                next + ("_nativeEdits" to (asMap(raw["_nativeEdits"]) + (id to op)))
            }
        }
        val pending = asMap(asMap(committed)["_nativeEdits"])[id]
        if (pending != null) finishEdit(company, hit.ref, id, asMap(pending))
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
    private suspend fun finishEdit(company: DatabaseReference, vehicle: DatabaseReference, id: String, op: Map<String, Any?>) {
        FleetEditEffects.finish(object : FleetEffectStore {
            override suspend fun mutate(path: String, transform: (Any?) -> Any?): Any? = transact(company.child(path), transform)
            override suspend fun vehicle(transform: (Any?) -> Any?): Any? = transact(vehicle, transform)
        }, id, op)
    }
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
    private suspend fun transact(ref: DatabaseReference, transform: (Any?) -> Any?): Any? {
        // Prime the local SDK cache: a first transaction callback may otherwise receive null
        // for an existing child, which is indistinguishable from a deleted record.
        ref.get().await()
        return suspendCancellableCoroutine { continuation ->
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
    }
    @Suppress("UNCHECKED_CAST")
    private fun asMap(value: Any?): Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
}
