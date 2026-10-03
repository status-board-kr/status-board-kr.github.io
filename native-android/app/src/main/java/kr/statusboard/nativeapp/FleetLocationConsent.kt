package kr.statusboard.nativeapp

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
    var consent by remember { mutableStateOf(FleetLocation.consent(context, session.cacheKey)) }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("직원 위치", fontSize = 16.sp)
            Text("근무시간 중 동의한 직원의 마지막 위치를 보여줍니다.", color = WebSub, fontSize = 12.sp)
            if (!session.isAdmin) Row {
                TextButton(onClick = { FleetLocation.revoke(context, session); consent = false; model.locationChanged() }) { Text("내 위치 공유 동의 해제") }
                Text(if (consent) "동의됨" else "동의하지 않음", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            }
            state.locations.keys().asSequence().forEach { uid ->
                state.locations.optJSONObject(uid)?.let { location ->
                    val member = state.members.optJSONObject(uid)
                    if (member != null) {
                        val lat = location.optDouble("lat"); val lng = location.optDouble("lng")
                        if (lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(member.optString("name").ifBlank { member.optString("email") }, fontSize = 14.sp)
                                Text(location.optString("at"), color = WebSub, fontSize = 10.sp)
                            }
                            TextButton(onClick = {
                                val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
                                if (map.resolveActivity(context.packageManager) != null) context.startActivity(map)
                                else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://map.naver.com/p/search/$lat,$lng")))
                            }) { Text("지도") }
                        }
                    }
                }
            }
            if (state.locations.length() == 0) Text("공유 중인 직원 위치가 없습니다.", color = WebSub, modifier = Modifier.padding(top = 20.dp))
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
}
