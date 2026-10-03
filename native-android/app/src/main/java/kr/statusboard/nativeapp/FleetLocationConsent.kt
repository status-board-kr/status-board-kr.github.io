package kr.statusboard.nativeapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

@Composable internal fun FleetLocationConsent(state: FleetUiState) {
    val session = state.session ?: return
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now()) }; var revision by remember { mutableIntStateOf(0) }
    var checked by remember(session.cacheKey) { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionDenied = !FleetLocation.permission(context); revision++; now = Instant.now()
    }
    DisposableEffect(lifecycle, session.cacheKey) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { now = Instant.now(); revision++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session.cacheKey) { while (true) { delay(30_000); now = Instant.now(); revision++ } }
    val decision = remember(now, revision, state.locationRevision, session, state.locationSettings.toString(), state.locationSettingsLoaded) {
        if (!state.locationSettingsLoaded) kr.statusboard.core.FleetLocationDecision(false, false)
        else FleetLocation.decide(context, session, state.locationSettings, now)
    }
    LaunchedEffect(decision.collect, revision, state.locationRevision, session.cacheKey) {
        if (decision.collect) {
            // Android 12+ foreground-service starts must originate from a resumed visible app.
            if (lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                try { FleetLocation.start(context, session); failure = "" }
                catch (_: Exception) { failure = "위치 공유 시작에 실패했습니다. 위치 권한과 휴대전화 설정을 확인해주세요." }
            }
        } else FleetLocation.stop(context)
    }
    if (decision.prompt || (decision.collect && failure.isNotBlank())) AlertDialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("근무 중 위치 공유 동의") }, text = { Column {
            Text("업무 차량 배차·회수와 직원 위치 확인을 위해 근무시간 중 위치를 같은 업체 직원에게 공유합니다.")
            Text("공유 시간: ${state.locationSettings.optString("start", "09:00")}~${state.locationSettings.optString("end", "18:00")}\n토요일·일요일·공휴일과 업무시간 밖에는 공유하지 않습니다.", modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp)
            Text("최초 동의는 유지되며 전체 메뉴의 내 위치 공유에서 해제할 수 있습니다.", modifier = Modifier.padding(top = 10.dp), fontSize = 12.sp)
            Row { Checkbox(checked, { checked = it }); Text("위치 공유에 동의합니다", modifier = Modifier.padding(top = 13.dp), fontSize = 13.sp) }
            if (permissionDenied) Text("휴대전화 위치 권한을 허용해야 공유가 시작됩니다.", fontSize = 12.sp)
            if (failure.isNotBlank()) Text(failure, fontSize = 12.sp)
        } },
        confirmButton = { TextButton(onClick = {
            FleetLocation.accept(context, session); revision++; now = Instant.now()
            if (!FleetLocation.permission(context)) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }, enabled = checked) { Text("동의하고 시작") } },
        dismissButton = { if (permissionDenied) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("위치 권한 설정") } })
}

@Composable internal fun FleetLocationDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current; val session = state.session ?: return
    var consent by remember(session.cacheKey) { mutableStateOf(FleetLocation.consent(context, session.cacheKey)) }
    var checked by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectionRevision by remember { mutableIntStateOf(0) }
    var mapError by remember { mutableStateOf("") }
    var addresses by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var startHour by remember(state.locationSettings.toString()) { mutableStateOf(state.locationSettings.optString("start", "09:00")) }
    var endHour by remember(state.locationSettings.toString()) { mutableStateOf(state.locationSettings.optString("end", "18:00")) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        now = Instant.now(); model.locationChanged()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { now = Instant.now(); model.locationChanged() } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(session.cacheKey) { while (true) { delay(10_000); now = Instant.now() } }
    val decision = FleetLocation.decide(context, session, state.locationSettings, now)
    val points = state.locations.keys().asSequence().mapNotNull { uid ->
        if (!session.isAdmin && !consent && uid != session.uid) return@mapNotNull null
        val location = state.locations.optJSONObject(uid) ?: return@mapNotNull null
        val member = state.members.optJSONObject(uid) ?: return@mapNotNull null
        val lat = location.optDouble("lat"); val lng = location.optDouble("lng")
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) null
        else FleetStaffPoint(uid, if (uid == session.uid) "나" else member.optString("name").ifBlank { member.optString("email") }, lat, lng, location.optString("at"))
    }.sortedBy { if (it.uid == session.uid) 0 else 1 }.toList()
    val status = when {
        !state.locationSettingsLoaded -> "회사 근무시간을 확인 중입니다…"
        !decision.collect -> FleetLocationHealth.label(decision.reason)
        !FleetLocation.enabled(context) -> "휴대전화 위치 기능이 꺼져 있습니다. 위치 설정에서 켜주세요."
        FleetLocationHealth.owner == session.cacheKey && FleetLocationHealth.message.isNotBlank() -> FleetLocationHealth.message
        else -> "위치 공유 준비 중…"
    }
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("📍 직원 위치", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("위치 공유를 켠 직원끼리만 서로 위치가 보여요. 🟢 실시간 · ⚪ 마지막으로 확인된 위치", color = WebSub, fontSize = 12.sp)
            Text("근무시간 ${state.locationSettings.optString("start", "09:00")}~${state.locationSettings.optString("end", "18:00")} · 주말·공휴일 제외", color = WebSub, fontSize = 12.sp)
            Text(status, modifier = Modifier.padding(vertical = 10.dp), fontSize = 12.sp)
            if (!session.isAdmin) {
                if (!consent) {
                    Row { Checkbox(checked, { checked = it }); Text("근무 중 위치 공유에 동의합니다", modifier = Modifier.padding(top = 13.dp), fontSize = 12.sp) }
                    TextButton(onClick = {
                        FleetLocation.accept(context, session); consent = true; model.locationChanged(); now = Instant.now()
                        if (!FleetLocation.permission(context)) permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, enabled = checked) { Text("동의하고 공유 시작") }
                }
                if (consent && !FleetLocation.permission(context)) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("위치 권한 설정") }
                if (!FleetLocation.enabled(context)) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("휴대전화 위치 켜기") }
                if (decision.collect && FleetLocation.enabled(context)) TextButton(onClick = {
                    scope.launch {
                        FleetLocation.stop(context); delay(500)
                        FleetLocationHealth.report(session.cacheKey, "위치 공유 다시 연결 중…")
                        runCatching { FleetLocation.start(context, session) }.onFailure { FleetLocationHealth.report(session.cacheKey, "위치 공유 시작 실패 · 위치 권한을 확인해주세요.") }
                        now = Instant.now(); model.locationChanged()
                    }
                }) { Text("위치 공유 다시 연결") }
            }
            if (session.isAdmin) {
                Text("⚙️ 위치 공유 시간 (관리자만 변경)", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { WebInput(startHour, { startHour = it }, placeholder = "09:00") }
                    Text("~", modifier = Modifier.padding(top = 10.dp))
                    Box(Modifier.weight(1f)) { WebInput(endHour, { endHour = it }, placeholder = "18:00") }
                    Button(onClick = { model.saveLocationHours(startHour, endHour) {} }, enabled = !state.cached && !state.sending && state.locationSettingsLoaded) { Text("저장") }
                }
                Spacer(Modifier.height(12.dp))
            }
            if (session.isAdmin || consent) {
            FleetLocationMap(points, selected, Modifier.fillMaxWidth().height(220.dp), { addresses = it }, selectionRevision) { mapError = it }
            if (mapError.isNotBlank()) Text(mapError, color = WebSub, fontSize = 12.sp)
            points.forEach { point ->
                val age = runCatching { java.time.Duration.between(Instant.parse(point.at), now).toMinutes() }.getOrNull()
                val minutes = runCatching { kotlin.math.round(java.time.Duration.between(Instant.parse(point.at), now).toMillis() / 60000.0).toLong().coerceAtLeast(0) }.getOrNull()
                val recent = age != null && age in 0L..10L
                val ago = when { minutes == null -> ""; minutes < 1 -> "방금 전"; minutes < 60 -> "${minutes}분 전"; else -> "${kotlin.math.round(minutes / 60.0).toLong()}시간 전" }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).alpha(if (recent) 1f else .6f).background(WebPanel2, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp))
                    .clickable { selected = point.uid; selectionRevision++ }.padding(12.dp, 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${if (recent) "🟢" else "⚪"} ${point.name}${if (point.uid == session.uid) " (나)" else ""}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(addresses[point.uid]?.takeIf { it.isNotBlank() } ?: "위치 확인 중...", fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
                        if (!recent) Text("앱이 꺼져 있거나 신호가 없어요", color = WebSub, fontSize = 11.sp)
                    }
                    Text("${if (recent) "" else "마지막 · "}$ago", color = WebSub, fontSize = 11.sp)
                }
            }
            if (points.isEmpty()) Text("공유 중인 직원 위치가 없습니다. 직원의 동의·위치 권한·근무시간을 확인해주세요.", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
            }
        }
        if (!session.isAdmin && consent) Button(onClick = { FleetLocation.revoke(context, session); consent = false; model.locationChanged() }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text("📍 내 위치 공유 끄기") }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
}
