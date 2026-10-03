package kr.statusboard.nativeapp

import android.app.job.*
import android.content.*
import android.os.PersistableBundle
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.*
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** An installation's native token never replaces the working web/Capacitor pushToken. */
object FleetPush {
    @Volatile var foreground = false
    @Volatile var visibleChatOwner: String? = null
    private const val PREFS = "native_push"
    private val mutex = Mutex()
    private var lastAttempt = 0L
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun installation(context: Context): String {
        val p = prefs(context)
        return p.getString("installation", null) ?: UUID.randomUUID().toString().also { p.edit().putString("installation", it).apply() }
    }
    fun status(context: Context, session: FleetSession): String {
        val p = prefs(context)
        if (!FleetNotifications.permitted(context)) return "휴대전화 알림 권한 필요"
        return if (p.getString("owner", null) == session.cacheKey && p.getBoolean("registered", false)) "전용 앱 알림 등록됨" else "전용 앱 알림 연결 확인 중"
    }
    suspend fun sync(context: Context, session: FleetSession) = mutex.withLock {
        val auth = FirebaseAuth.getInstance(); val p = prefs(context)
        if (auth.currentUser?.uid != session.uid || !FleetNotifications.permitted(context)) return@withLock
        val owner = p.getString("owner", null)
        val now = System.currentTimeMillis()
        if (owner == session.cacheKey && p.getBoolean("registered", false) && now - p.getLong("at", 0) < 86_400_000L) return@withLock
        if (owner == session.cacheKey && now - lastAttempt < 60_000L) return@withLock
        lastAttempt = now
        val verified = FleetAccessResolver(NativeMembership(FleetTransport(auth))).resolve(session.uid)
        if (verified?.cacheKey != session.cacheKey) return@withLock
        p.edit().putString("owner", session.cacheKey).putBoolean("registered", false).apply()
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        val token = FirebaseMessaging.getInstance().token.await()
        if (auth.currentUser?.uid != session.uid || p.getString("owner", null) != session.cacheKey) return@withLock
        val ref = FirebaseDatabase.getInstance().getReference(session.path("members/${session.uid}/nativePushTokens/${installation(context)}"))
        ref.setValue(mapOf("token" to token, "platform" to "android-native", "at" to now)).await()
        if (auth.currentUser?.uid == session.uid && p.getString("owner", null) == session.cacheKey)
            p.edit().putBoolean("registered", true).putLong("at", now).apply()
    }
    fun invalidate(context: Context) { prefs(context).edit().putBoolean("registered", false).putLong("at", 0).apply(); lastAttempt = 0 }
    fun queueRefresh(context: Context) {
        val owner = prefs(context).getString("owner", null) ?: return
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (!owner.startsWith("$uid:")) return
        val company = owner.removePrefix("$uid:")
        if (runCatching { FleetSession(uid, company, "staff") }.isFailure) return
        val extras = PersistableBundle().apply { putString("uid", uid); putString("company", company); putBoolean("refresh", true) }
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(2467103, ComponentName(context, FleetPushJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras).build())
    }
    fun clear(context: Context) {
        prefs(context).edit().remove("owner").remove("at").remove("seen").putBoolean("registered", false).apply()
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.allPendingJobs.filter { it.service.className == FleetPushJobService::class.java.name }.forEach { scheduler.cancel(it.id) }
        lastAttempt = 0
    }
    suspend fun detach(context: Context, session: FleetSession?) {
        clear(context)
        if (session == null || FirebaseAuth.getInstance().currentUser?.uid != session.uid) return
        // Clear the local owner first so a racing FCM cannot show a logged-out company's message.
        mutex.withLock {
            FirebaseDatabase.getInstance().getReference(session.path("members/${session.uid}/nativePushTokens/${installation(context)}")).removeValue().await()
        }
    }
    fun queue(context: Context, message: RemoteMessage) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val company = message.data["companyId"] ?: return
        val session = runCatching { FleetSession(uid, company, "staff") }.getOrNull() ?: return
        if (prefs(context).getString("owner", null) != session.cacheKey || !FleetNotifications.permitted(context)) return
        val id = message.data["messageId"]?.takeIf { it.length <= 160 && it.none { c -> c in ".#$[]/" } }
            ?: message.messageId ?: return
        enqueue(context, uid, company, id, message.data["kind"] ?: "chat")
    }
    fun queueChat(context: Context, session: FleetSession, id: String) {
        if (FirebaseAuth.getInstance().currentUser?.uid != session.uid || prefs(context).getString("owner", null) != session.cacheKey) return
        enqueue(context, session.uid, session.companyId, id, "chat")
    }
    private fun enqueue(context: Context, uid: String, company: String, id: String, kind: String) {
        if (!FleetNotifications.permitted(context)) return
        val extras = PersistableBundle().apply { putString("uid", uid); putString("company", company); putString("id", id); putString("kind", kind) }
        // Persist only routing identifiers. Notification/customer text stays on the server.
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(("push:$id".hashCode() and Int.MAX_VALUE), ComponentName(context, FleetPushJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setOverrideDeadline(0).setExtras(extras).build())
    }
    suspend fun deliver(context: Context, params: JobParameters) {
        val uid = params.extras.getString("uid") ?: return; val company = params.extras.getString("company") ?: return
        val auth = FirebaseAuth.getInstance(); val p = prefs(context)
        val expected = "$uid:$company"
        if (auth.currentUser?.uid != uid || p.getString("owner", null) != expected || !FleetNotifications.permitted(context)) return
        val session = FleetAccessResolver(NativeMembership(FleetTransport(auth))).resolve(uid) ?: return
        if (session.cacheKey != expected) return
        if (params.extras.getBoolean("refresh")) { sync(context, session); return }
        val id = params.extras.getString("id") ?: return
        val seen = p.getStringSet("seen", emptySet()).orEmpty()
        if (id in seen) return
        // Same identifier replaces an earlier delivery; no private message body in notification storage.
        if (auth.currentUser?.uid != uid || p.getString("owner", null) != expected) return
        val inquiry = params.extras.getString("kind") == "inquiry"
        if (inquiry || !foreground || visibleChatOwner != expected) FleetNotifications.show(context, "push:$id", if (inquiry) "현황판 상담 신청" else "현황판 메신저",
            if (inquiry) "새 상담 신청이 도착했습니다. 눌러서 상담 목록을 확인하세요." else "새 메시지가 도착했습니다. 눌러서 대화를 확인하세요.",
            if (inquiry) "inquiries" else "chat")
        p.edit().putStringSet("seen", (seen.takeLastSafe(199) + id).toSet()).apply()
    }
    private fun Set<String>.takeLastSafe(limit: Int) = toList().takeLast(limit)

    /** Called only after the user's message has been committed; push failure never resends the chat. */
    suspend fun notifyChat(session: FleetSession, id: String, text: String, title: String) = withContext(Dispatchers.IO) {
        val user = FirebaseAuth.getInstance().currentUser?.takeIf { it.uid == session.uid } ?: return@withContext
        val token = user.getIdToken(false).await().token ?: return@withContext
        val connection = URL("https://sendchatpush-613571323567.asia-northeast3.run.app").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 15000; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $token"); connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("companyId", session.companyId).put("messageId", id).put("title", title.take(40)).put("body", text.take(120)).toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "알림 요청 실패" }
        } finally { connection.disconnect() }
    }
}

class FleetMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        FleetPush.invalidate(this)
        FleetPush.queueRefresh(this)
    }
    override fun onMessageReceived(message: RemoteMessage) { FleetPush.queue(this, message) }
}

class FleetPushJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tasks = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        tasks[params.jobId] = scope.launch {
            var retry = false
            try { FleetPush.deliver(this@FleetPushJobService, params) }
            catch (error: Exception) { if (error is CancellationException) throw error; retry = error !is AccessDenied }
            finally { withContext(NonCancellable + Dispatchers.Main) { tasks.remove(params.jobId); jobFinished(params, retry) } }
        }; return true
    }
    override fun onStopJob(params: JobParameters): Boolean { tasks.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
