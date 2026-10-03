package kr.statusboard.nativeapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kr.statusboard.core.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

object FleetLocation {
    const val CONSENT = "native_location_consent"
    @Volatile private var calendar: FleetHolidayCalendar? = null
    fun holidays(context: Context): FleetHolidayCalendar {
        calendar?.let { return it }
        val data = JSONObject(context.assets.open("work-holidays.json").bufferedReader().use { it.readText() })
        val years = data.getJSONArray("verifiedYears"); val dates = data.getJSONArray("dates")
        return FleetHolidayCalendar((0 until years.length()).map { years.getInt(it) }.toSet(),
            (0 until dates.length()).map { LocalDate.parse(dates.getString(it)) }.toSet()).also { calendar = it }
    }
    fun permission(context: Context): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun consent(context: Context, key: String) = context.getSharedPreferences(CONSENT, Context.MODE_PRIVATE).getBoolean(key, false)
    fun decide(context: Context, session: FleetSession, settings: JSONObject, now: Instant = Instant.now()): FleetLocationDecision {
        val start = runCatching { LocalTime.parse(settings.optString("start", "09:00")) }.getOrNull()
        val end = runCatching { LocalTime.parse(settings.optString("end", "18:00")) }.getOrNull()
        if (start == null || end == null) return FleetLocationDecision(false, false)
        return FleetLocationPolicy.decide(now, session.isAdmin, consent(context, session.cacheKey), permission(context), start, end, holidays(context))
    }
    fun accept(context: Context, session: FleetSession) { context.getSharedPreferences(CONSENT, Context.MODE_PRIVATE).edit().putBoolean(session.cacheKey, true).apply() }
    fun revoke(context: Context, session: FleetSession) {
        context.getSharedPreferences(CONSENT, Context.MODE_PRIVATE).edit().putBoolean(session.cacheKey, false).apply(); stop(context)
    }
    fun start(context: Context, session: FleetSession) {
        if (FleetLocationService.activeKey == session.cacheKey) return
        ContextCompat.startForegroundService(context, Intent(context, FleetLocationService::class.java)
            .putExtra("uid", session.uid).putExtra("company", session.companyId))
    }
    fun stop(context: Context) { context.stopService(Intent(context, FleetLocationService::class.java)) }
}
