package kr.statusboard.nativeapp

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.FleetSession
import org.json.JSONObject
import java.time.Instant

/** Starts only from a visible app with consent and location permission; never starts at boot. */
class FleetLocationService : Service() {
    companion object { @Volatile var activeKey: String? = null; private set }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var session: FleetSession? = null
    private var settings = JSONObject()
    private var memberListener: ValueEventListener? = null; private var settingsListener: ValueEventListener? = null
    private var company: DatabaseReference? = null
    private var requesting = false; private var saving = false
    private var initializing = false
    private var last: android.location.Location? = null; private var lastSaved = 0L
    private val firstFix = com.google.android.gms.tasks.CancellationTokenSource()
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::saveLocation)
        }
        override fun onLocationAvailability(value: LocationAvailability) {
            if (!value.isLocationAvailable && last == null) report("GPS 위치를 기다리는 중입니다. 휴대전화 위치 기능과 신호를 확인해주세요.")
        }
    }
    private fun report(text: String) { session?.let { FleetLocationHealth.report(it.cacheKey, text) } }
    private fun saveLocation(location: android.location.Location) {
            val active = session ?: return
            val age = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
            if (!allowed() || saving || age !in 0L..120_000L || !location.latitude.isFinite() || !location.longitude.isFinite()
                || location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
            val now = System.currentTimeMillis()
            if (last != null && location.distanceTo(last!!) < 30 && now - lastSaved < 300_000) return
            saving = true
            scope.launch {
                try {
                    if (!allowed()) return@launch
                    company!!.child("locations/${active.uid}").setValue(mapOf("lat" to location.latitude, "lng" to location.longitude,
                        "email" to FirebaseAuth.getInstance().currentUser?.email.orEmpty(), "at" to Instant.now().toString())).await()
                    last = location; lastSaved = now
                    report("위치 공유 중 · 최근 저장 ${Instant.now().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime().toString().take(8)}")
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    report("위치 저장 실패 · 회사 접근 권한과 인터넷 연결을 확인해주세요.")
                }
                finally { saving = false }
            }
    }
    private fun allowed(): Boolean {
        val current = session ?: return false
        return FirebaseAuth.getInstance().currentUser?.uid == current.uid && FleetLocation.decide(this, current, settings).collect
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("fleet_location", "근무 중 위치 공유", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 8301, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(8301, Notification.Builder(this, "fleet_location").setSmallIcon(R.drawable.menu_location)
            .setContentTitle("현황판 · 근무 중 위치 공유").setContentText("근무시간 종료·휴일·동의 해제 시 공유가 종료됩니다.")
            .setContentIntent(open).setOngoing(true).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uid = intent?.getStringExtra("uid"); val companyId = intent?.getStringExtra("company")
        if (uid == null || companyId == null || FirebaseAuth.getInstance().currentUser?.uid != uid) { stopSelf(); return START_NOT_STICKY }
        if (initializing || session?.cacheKey == "$uid:$companyId") return START_NOT_STICKY
        if (session != null) { stopSelf(); return START_NOT_STICKY }
        val initial = runCatching { FleetSession(uid, companyId, "staff") }.getOrNull() ?: run { stopSelf(); return START_NOT_STICKY }
        initializing = true
        FleetLocationHealth.report(initial.cacheKey, "직원 권한과 근무시간 확인 중…")
        scope.launch {
            try {
                val resolved = kr.statusboard.core.FleetAccessResolver(NativeMembership(FleetTransport(FirebaseAuth.getInstance()))).resolve(uid)
                if (resolved == null || resolved.cacheKey != initial.cacheKey || resolved.isAdmin) { stopSelf(); return@launch }
                session = resolved; company = FirebaseDatabase.getInstance().getReference(resolved.path(""))
                val loaded = company!!.child("locationSettings").get().await()
                settings = JSONObject((loaded.value as? Map<*, *>) ?: emptyMap<String, Any>())
                if (!allowed()) { report(FleetLocationHealth.label(FleetLocation.decide(this@FleetLocationService, resolved, settings).reason)); stopSelf(); return@launch }
                memberListener = object : ValueEventListener {
                    override fun onDataChange(value: DataSnapshot) { if (!value.exists() || value.child("role").value == "owner") stopSelf() }
                    override fun onCancelled(error: DatabaseError) { stopSelf() }
                }.also { company!!.child("members/$uid").addValueEventListener(it) }
                settingsListener = object : ValueEventListener {
                    override fun onDataChange(value: DataSnapshot) { settings = JSONObject((value.value as? Map<*, *>) ?: emptyMap<String, Any>()); if (!allowed()) stopSelf() }
                    override fun onCancelled(error: DatabaseError) { stopSelf() }
                }.also { company!!.child("locationSettings").addValueEventListener(it) }
                activeKey = resolved.cacheKey
                requestLocation()
                while (isActive) { delay(30_000); if (!allowed() || !FleetLocation.enabled(this@FleetLocationService)) { if (!FleetLocation.enabled(this@FleetLocationService)) report("휴대전화 위치 기능이 꺼져 있습니다. 위치 설정에서 켜주세요."); stopSelf(); break } }
            } catch (error: Exception) { if (error is CancellationException) throw error; FleetLocationHealth.report(initial.cacheKey, "위치 공유 시작 실패 · 회사 연결과 위치 권한을 확인해주세요."); stopSelf() }
            finally { initializing = false }
        }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission") private fun requestLocation() {
        if (!allowed()) { stopSelf(); return }
        if (!FleetLocation.enabled(this)) { report("휴대전화 위치 기능이 꺼져 있습니다. 위치 설정에서 켜주세요."); stopSelf(); return }
        report("GPS 위치를 확인 중…")
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000).setMinUpdateIntervalMillis(60_000).build()
        requesting = true
        client.requestLocationUpdates(request, callback, Looper.getMainLooper()).addOnFailureListener { report("GPS 요청 실패 · 위치 권한과 Google Play 서비스를 확인해주세요."); stopSelf() }
        client.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(30_000).setDurationMillis(30_000).build(), firstFix.token)
            .addOnSuccessListener { location -> if (location != null) saveLocation(location) else if (last == null) report("GPS 위치를 아직 찾지 못했습니다. 휴대전화 위치 설정과 신호를 확인해주세요.") }
            .addOnFailureListener { if (last == null) report("현재 위치 조회 실패 · 휴대전화 위치 권한을 확인해주세요.") }
    }
    override fun onDestroy() {
        activeKey = null
        firstFix.cancel()
        if (requesting) client.removeLocationUpdates(callback)
        memberListener?.let { company?.child("members/${session?.uid}")?.removeEventListener(it) }
        settingsListener?.let { company?.child("locationSettings")?.removeEventListener(it) }
        session?.let { company?.child("locations/${it.uid}")?.removeValue() }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
