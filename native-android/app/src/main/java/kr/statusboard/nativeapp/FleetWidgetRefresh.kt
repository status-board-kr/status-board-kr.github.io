package kr.statusboard.nativeapp

import android.app.Activity
import android.app.job.*
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.FleetAccessResolver
import org.json.JSONObject

/** Refreshes only installed widgets, at Android's 15 minute minimum; no five-second polling. */
object FleetWidgetRefresh {
    private const val PERIODIC = 849100
    private const val IMMEDIATE = 849101
    private fun installed(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return listOf(FleetCalendarWidget::class.java, FleetCompactWidget::class.java).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }
    @JvmStatic fun enqueue(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (!installed(context) || FirebaseAuth.getInstance().currentUser == null) { cancel(context); return }
        val component = ComponentName(context, FleetWidgetRefreshService::class.java)
        if (jobs.getPendingJob(PERIODIC) == null) jobs.schedule(JobInfo.Builder(PERIODIC, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L).setPersisted(true).build())
        if (jobs.getPendingJob(IMMEDIATE) == null) jobs.schedule(JobInfo.Builder(IMMEDIATE, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun maintain(context: Context) {
        // Avoid scheduling an immediate network read on every live UI change.
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (installed(context) && jobs.getPendingJob(PERIODIC) == null) enqueue(context)
    }
    fun cancel(context: Context) { val jobs = context.getSystemService(JobScheduler::class.java); jobs.cancel(PERIODIC); jobs.cancel(IMMEDIATE) }
    suspend fun refresh(context: Context) {
        val auth = FirebaseAuth.getInstance(); val uid = auth.currentUser?.uid ?: run { FleetWidgets.clear(context); return }
        val transport = FleetTransport(auth)
        val session = FleetAccessResolver(NativeMembership(transport)).resolve(uid) ?: throw AccessDenied()
        val vehicles = VehicleCodec.decode(transport.read(session.path("vehicles")))
        val schedules = transport.read(session.path("schedules")) as? JSONObject ?: JSONObject()
        val profile = transport.read(session.path("profile")) as? JSONObject ?: JSONObject()
        val jobs = transport.read(session.path("wookyJobs")) as? JSONObject ?: JSONObject()
        // Fetch only recent message metadata; photos live at a separate path.
        val recent = com.google.firebase.database.FirebaseDatabase.getInstance().getReference(session.path("chat"))
            .orderByKey().limitToLast(50).get().awaitJson()
        if (auth.currentUser?.uid != uid) return
        val current = FleetAccessResolver(NativeMembership(transport)).resolve(uid)
        if (current?.cacheKey != session.cacheKey) throw AccessDenied()
        val state = FleetUiState(signedIn = true, session = current, vehicles = vehicles, schedules = schedules, scheduleLoaded = true,
            longBranch = profile.optString("longTermBranch").ifBlank { "장기" }, wookyJobs = jobs, chat = recent)
        FleetWidgets.publish(context, state) { if (auth.currentUser?.uid == uid) current.cacheKey else null }
        if (auth.currentUser?.uid == uid) FleetNotifications.schedule(context, state)
    }
    private suspend fun com.google.android.gms.tasks.Task<com.google.firebase.database.DataSnapshot>.awaitJson(): JSONObject {
        val value = this.await().value
        return JSONObject((value as? Map<*, *>) ?: emptyMap<String, Any>())
    }
    @JvmStatic fun refreshPopup(activity: Activity, loaded: Runnable): Job = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
        try { refresh(activity); if (!activity.isFinishing && !activity.isDestroyed) loaded.run() }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is AccessDenied) { FleetWidgets.clear(activity); if (!activity.isFinishing && !activity.isDestroyed) loaded.run() }
        }
    }
}

class FleetWidgetRefreshService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableMapOf<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            var retry = false
            try { FleetWidgetRefresh.refresh(this@FleetWidgetRefreshService) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is AccessDenied) FleetWidgets.clear(this@FleetWidgetRefreshService) else retry = true
            } finally { withContext(NonCancellable + Dispatchers.Main) { running.remove(params.jobId); jobFinished(params, retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running.remove(params.jobId)?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
