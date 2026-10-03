package kr.statusboard.nativeapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
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
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("📱 처음 한 번 설정해주세요", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("이 3가지를 해두어야 앱을 닫아도 근무시간에 위치가 공유되고, 메신저 알림이 옵니다.\n\n① 위치 권한 → 허용\n② 알림 권한 → 허용\n③ 배터리 → 제한 없음\n\n버튼을 누르면 해당 설정 화면이 바로 열려요.", color = WebSub, fontSize = 13.sp, modifier = Modifier.padding(vertical = 14.dp))
            Button(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("① 위치·앱 권한 설정 열기") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("② 알림 설정 열기") }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("③ 배터리 설정 열기") }
            OutlinedButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !FleetNotifications.permitted(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                else {
                    FleetNotifications.channel(context)
                    val open = android.app.PendingIntent.getActivity(context, 8302, Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    context.getSystemService(android.app.NotificationManager::class.java).notify(8302, android.app.Notification.Builder(context, FleetNotifications.CHANNEL).setSmallIcon(R.drawable.menu_chat).setContentTitle("현황판 · 알림 시험").setContentText("이 휴대전화의 알림 소리를 확인해주세요.").setContentIntent(open).setAutoCancel(true).build())
                }
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("🔔 알림 소리 시험하기") }
            Text("화면 색상", modifier = Modifier.padding(top = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to "화이트", true to "블랙").forEach { (dark, label) ->
                    FilterChip(selected = FleetAppearance.dark == dark, onClick = { FleetAppearance.select(context, dark) }, label = { Text(label) })
                }
            }
            Text("이 휴대전화에 저장되며 다시 실행해도 유지됩니다.", color = WebSub, fontSize = 11.sp)
            Text("알림: ${if (allowed) "허용됨" else "휴대전화에서 허용 필요"}", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
            Text(FleetPush.status(context, session), color = WebSub, fontSize = 12.sp)
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
