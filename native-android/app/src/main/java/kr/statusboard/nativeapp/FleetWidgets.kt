package kr.statusboard.nativeapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kr.statusboard.core.FleetWidgetSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

internal fun plainJson(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { plainJson(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { plainJson(value.opt(it)) }
    else -> value
}
@Suppress("UNCHECKED_CAST") internal fun jsonMap(value: JSONObject?): Map<String, Any?> = plainJson(value) as? Map<String, Any?> ?: emptyMap()
object FleetWidgets {
    suspend fun publish(context: Context, state: FleetUiState, currentKey: () -> String?) = withContext(Dispatchers.Default) {
        if (state.session == null || !state.signedIn || !state.scheduleLoaded || state.cached) return@withContext
        val schedules = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }
            .map { @Suppress("UNCHECKED_CAST") (plainJson(it) as Map<String, Any?>) }.toList()
        val health = state.wookyJobs.keys().asSequence().mapNotNull { state.wookyJobs.optJSONObject(it)?.optJSONObject("agentHealth") }
            .maxByOrNull { it.optString("lastSeen") }
        val readAt = context.getSharedPreferences("native_chat_read", Context.MODE_PRIVATE).getLong(state.session.cacheKey, 0)
        val unread = state.chat.keys().asSequence().mapNotNull { state.chat.optJSONObject(it) }.count {
            it.optString("uid") != state.session.uid && runCatching { Instant.parse(it.optString("at")).toEpochMilli() > readAt }.getOrDefault(false)
        }
        @Suppress("UNCHECKED_CAST")
        val data = FleetWidgetSnapshot.build(state.vehicles, schedules, state.longBranch, unread,
            plainJson(health) as? Map<String, Any?>, Instant.now())
        ensureActive()
        withContext(Dispatchers.Main) {
            if (currentKey() != state.session.cacheKey) return@withContext
            context.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).edit()
                .putString("owner", state.session.cacheKey).putLong("verifiedAt", System.currentTimeMillis()).putString("data", JSONObject(data).toString()).apply()
            FleetWidgetUtil.refreshAll(context); FleetWidgetWeather.refresh(context)
            FleetWidgetRefresh.maintain(context)
        }
    }
    fun clear(context: Context) {
        FleetWidgetRefresh.cancel(context)
        context.getSharedPreferences("fleet_widget", Context.MODE_PRIVATE).edit().clear().apply()
        FleetWidgetUtil.refreshAll(context)
    }
    fun markChatRead(context: Context, key: String) {
        context.getSharedPreferences("native_chat_read", Context.MODE_PRIVATE).edit().putLong(key, System.currentTimeMillis()).apply()
    }
}
