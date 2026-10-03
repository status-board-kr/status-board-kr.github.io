package kr.statusboard.nativeapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun FleetNotificationPermission(state: FleetUiState, model: FleetViewModel) {
    val session = state.session ?: return; val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.locationChanged() }
    LaunchedEffect(session.cacheKey) {
        val prefs = context.getSharedPreferences("native_notification_permission", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !FleetNotifications.permitted(context) && !prefs.getBoolean(session.uid, false)) {
            prefs.edit().putBoolean(session.uid, true).apply(); launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable internal fun FleetAppSettings(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current; val session = state.session ?: return
    var revision by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { revision++; model.locationChanged() }
    val allowed = remember(revision) { FleetNotifications.permitted(context) }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
            Text("내 앱 설정", fontSize = 18.sp)
            Text("알림: ${if (allowed) "허용됨" else "휴대전화에서 허용 필요"}", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !allowed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("알림 허용 · 설정") }
            Text("반납 전날 18시·당일 9시, 결제일 9시, 회수 미청구 안내. 휴대전화 절전 설정과 인터넷 연결에 따라 늦어질 수 있습니다.", color = WebSub, fontSize = 11.sp)
            if (!session.isAdmin) {
                Text("위치 공유 동의: ${if (FleetLocation.consent(context, session.cacheKey)) "유지 중" else "해제됨"}", modifier = Modifier.padding(top = 18.dp), fontSize = 12.sp)
                TextButton(onClick = { FleetLocation.revoke(context, session); revision++; model.locationChanged() }) { Text("위치 공유 동의 해제") }
            }
            Text("홈 위젯", modifier = Modifier.padding(top = 18.dp))
            Text("기존형은 날짜를 선택하면 위젯 안에 일정이 표시됩니다. 간편형은 날짜를 누르면 홈 화면 위로 일정 팝업이 뜹니다.", color = WebSub, fontSize = 12.sp)
            Text("버전 ${BuildConfig.VERSION_NAME}", color = WebSub, modifier = Modifier.padding(top = 24.dp), fontSize = 11.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
}
