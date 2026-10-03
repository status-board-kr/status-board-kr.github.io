package kr.statusboard.nativeapp

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.DataSnapshot
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.Instant

/** Signup paths use the same company/member/userIndex schema as fleet-auth.js. */
class FleetEnrollment(private val auth: FirebaseAuth, private val transport: FleetTransport) {
    private suspend fun ownRecord(ref: DatabaseReference): DataSnapshot? = try { ref.get().await() } catch (error: Exception) {
        // The original rules may allow creating a new own member/company while denying
        // reads of the not-yet-existing record. Network failures must still stop signup.
        if (error.message?.contains("permission denied", ignoreCase = true) == true) null else throw error
    }
    suspend fun enroll(email: String, password: String, companyName: String?, invite: String?) {
        if (auth.currentUser == null) {
            require(password.length >= 6) { "비밀번호는 6자 이상 입력해주세요." }
            auth.createUserWithEmailAndPassword(email.trim(), password).await()
        }
        val user = auth.currentUser ?: throw AccessDenied()
        val index = transport.read("userIndex/${user.uid}") as? JSONObject
        check(index?.optString("companyId").isNullOrBlank()) { "이미 업체에 소속된 계정입니다. 로그인해주세요." }
        val db = FirebaseDatabase.getInstance(); val now = Instant.now().toString()
        val company = if (invite != null) {
            val code = invite.trim().uppercase(); require(code.matches(Regex("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}"))) { "초대코드 6자리를 확인해주세요." }
            val entry = transport.read("inviteIndex/$code") as? JSONObject ?: error("초대코드를 찾을 수 없습니다.")
            val id = entry.optString("companyId"); kr.statusboard.core.FleetSession(user.uid, id, "staff")
            val member = db.getReference("companies/$id/members/${user.uid}")
            val existing = ownRecord(member)
            if (existing?.exists() != true) member.setValue(mapOf("role" to "staff", "email" to user.email, "joinedAt" to now, "viaCode" to code)).await()
            id
        } else {
            val name = companyName?.trim().orEmpty(); require(name.isNotBlank() && name.length <= 60) { "업체명을 입력해주세요." }
            // Stable uid-derived id lets a company-created/index-write-failed signup recover.
            val id = "c_native_${user.uid}"; val companyRef = db.getReference("companies/$id")
            val existing = ownRecord(companyRef)
            if (existing?.exists() != true) companyRef.setValue(mapOf("profile" to mapOf("name" to name, "createdAt" to now, "ownerEmail" to user.email,
                "subscription" to mapOf("status" to "trial", "startedAt" to now)),
                "members" to mapOf(user.uid to mapOf("role" to "owner", "email" to user.email, "joinedAt" to now)))).await()
            else check(existing.child("members/${user.uid}/role").value == "owner") { "업체 접근 권한을 확인할 수 없습니다." }
            id
        }
        db.getReference("userIndex/${user.uid}").setValue(mapOf("companyId" to company)).await()
    }
}
