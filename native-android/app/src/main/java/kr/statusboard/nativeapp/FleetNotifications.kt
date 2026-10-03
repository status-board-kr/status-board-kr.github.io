package kr.statusboard.nativeapp

import android.Manifest
import android.app.*
import android.app.job.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kr.statusboard.core.*
import org.json.JSONObject
import java.time.Instant

object FleetNotifications {
    private const val PREFS = "native_alarms"
    const val CHANNEL = "fleet_alerts_native_v1"
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun intent(context: Context, seed: String) = Intent(context, FleetAlarmReceiver::class.java).setAction("fleet.native.ALARM").setData(android.net.Uri.parse("fleet-alarm:///${android.net.Uri.encode(seed)}"))
    private fun pending(context: Context, seed: String, source: Intent = intent(context, seed)) = PendingIntent.getBroadcast(context, 0, source, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun channel(context: Context) { context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "차량·결제·회수 알림", NotificationManager.IMPORTANCE_HIGH)) }
    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); val manager = context.getSystemService(AlarmManager::class.java)
        prefs.getStringSet("seeds", emptySet())!!.forEach { manager.cancel(pending(context, it)) }
        prefs.edit().clear().apply()
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.activeNotifications.filter { it.notification.channelId == CHANNEL }.forEach { notifications.cancel(it.id) }
        context.getSystemService(JobScheduler::class.java).allPendingJobs.filter { it.service.className == FleetAlarmJobService::class.java.name }
            .forEach { context.getSystemService(JobScheduler::class.java).cancel(it.id) }
    }
    fun schedule(context: Context, state: FleetUiState) {
        val session = state.session ?: return
        if (state.cached || !state.scheduleLoaded || !permitted(context)) return
        val records = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }.map(::jsonMap).toList()
        schedule(context, session, FleetAlarms.build(state.vehicles, records, Instant.now()), Instant.now())
    }
    private fun schedule(context: Context, session: FleetSession, alarms: List<FleetAlarm>, created: Instant) {
        channel(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); val manager = context.getSystemService(AlarmManager::class.java)
        val keys = alarms.map { it.seed }.toSet()
        (prefs.getStringSet("seeds", emptySet())!! - keys).forEach { manager.cancel(pending(context, it)) }
        alarms.forEach { alarm ->
            val data = intent(context, alarm.seed).putExtra("seed", alarm.seed).putExtra("uid", session.uid).putExtra("company", session.companyId)
                .putExtra("created", created.toEpochMilli()).putExtra("at", alarm.at.toEpochMilli())
            // No special exact-alarm privilege is required. Android power saving may delay delivery.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.at.toEpochMilli(), pending(context, alarm.seed, data))
        }
        prefs.edit().putString("owner", session.cacheKey).putStringSet("seeds", keys).apply()
    }
    suspend fun refresh(context: Context) {
        val auth = FirebaseAuth.getInstance(); val uid = auth.currentUser?.uid ?: return
        val transport = FleetTransport(auth); val session = FleetAccessResolver(NativeMembership(transport)).resolve(uid) ?: return
        val owner = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("owner", null)
        if (owner != session.cacheKey || !permitted(context)) return
        val vehicles = VehicleCodec.decode(transport.read(session.path("vehicles")))
        val schedules = transport.read(session.path("schedules")) as? JSONObject ?: JSONObject()
        val records = schedules.keys().asSequence().mapNotNull { schedules.optJSONObject(it) }.map(::jsonMap).toList()
        schedule(context, session, FleetAlarms.build(vehicles, records, Instant.now()), Instant.now())
    }
    suspend fun deliver(context: Context, source: Intent) {
        val auth = FirebaseAuth.getInstance(); val uid = source.getStringExtra("uid") ?: return
        val company = source.getStringExtra("company") ?: return
        val key = "$uid:$company"
        if (auth.currentUser?.uid != uid || !permitted(context) || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("owner", null) != key) return
        val transport = FleetTransport(auth); val session = FleetAccessResolver(NativeMembership(transport)).resolve(uid) ?: return
        if (session.cacheKey != key) return
        val created = Instant.ofEpochMilli(source.getLongExtra("created", 0)); val at = source.getLongExtra("at", 0)
        if (System.currentTimeMillis() - at !in 0..86_400_000L) return
        val vehicles = VehicleCodec.decode(transport.read(session.path("vehicles")))
        val schedules = transport.read(session.path("schedules")) as? JSONObject ?: JSONObject()
        val records = schedules.keys().asSequence().mapNotNull { schedules.optJSONObject(it) }.map(::jsonMap).toList()
        val alarm = FleetAlarms.build(vehicles, records, created).firstOrNull { it.seed == source.getStringExtra("seed") } ?: return
        // Recheck identity after network awaits; logout must suppress stale company notifications.
        if (auth.currentUser?.uid != uid || context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("owner", null) != key) return
        show(context, alarm.seed, alarm.title, alarm.body, alarm.open)
    }
    fun show(context: Context, id: String, title: String, body: String, open: String) {
        if (!permitted(context)) return
        channel(context)
        val tap = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java).putExtra("fleetOpen", open)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.menu_board).setContentTitle(title).setContentText(body).setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(tap).setAutoCancel(true).build())
    }
}

class FleetAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val refresh = intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
        if (!refresh && intent.action != "fleet.native.ALARM") return
        val extras = PersistableBundle().apply {
            putBoolean("refresh", refresh)
            listOf("seed", "uid", "company").forEach { putString(it, intent.getStringExtra(it)) }
            putLong("created", intent.getLongExtra("created", 0)); putLong("at", intent.getLongExtra("at", 0))
        }
        val id = (intent.getStringExtra("seed") ?: "refresh").hashCode() and Int.MAX_VALUE
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(id, ComponentName(context, FleetAlarmJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
}

class FleetAlarmJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        jobs[params.jobId] = scope.launch {
            var retry = false
            try {
                if (params.extras.getBoolean("refresh")) FleetNotifications.refresh(this@FleetAlarmJobService)
                else {
                    val source = Intent().apply {
                        listOf("seed", "uid", "company").forEach { putExtra(it, params.extras.getString(it)) }
                        putExtra("created", params.extras.getLong("created")); putExtra("at", params.extras.getLong("at"))
                    }
                    FleetNotifications.deliver(this@FleetAlarmJobService, source)
                }
            } catch (error: Exception) { if (error is CancellationException) throw error; retry = error !is AccessDenied }
            finally { withContext(NonCancellable + Dispatchers.Main) { jobs.remove(params.jobId); jobFinished(params, retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { jobs.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
