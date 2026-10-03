package kr.statusboard.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant

@Composable internal fun FleetCompanySettings(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    LaunchedEffect(state.session?.cacheKey) { model.loadSettings() }
    DisposableEffect(Unit) { onDispose { model.clearSettings() } }
    val source = state.companySettings
    val fields = remember(source) { mutableStateMapOf<String, String>().apply {
        val profile = source?.optJSONObject("profile"); val ai = source?.optJSONObject("aiSettings")
        put("name", profile?.optString("name").orEmpty().ifBlank { state.companyName })
        put("homeBranch", profile?.optString("homeBranch").orEmpty().ifBlank { state.homeBranch })
        put("longTermBranch", profile?.optString("longTermBranch").orEmpty().ifBlank { state.longBranch })
        put("geminiKey", ai?.optString("geminiKey").orEmpty().ifBlank { if (ai?.optString("provider") == "gemini") ai.optString("key") else "" }); put("grokKey", ai?.optString("grokKey").orEmpty())
        put("start", state.locationSettings.optString("start", "09:00")); put("end", state.locationSettings.optString("end", "18:00"))
    } }
    val editable = source != null && state.session?.isAdmin == true && !state.sending && !state.cached
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("회사 설정", fontSize = 18.sp)
            if (source == null) Text("설정 불러오는 중…", color = WebSub, fontSize = 12.sp)
            listOf("name" to "회사명", "homeBranch" to "기본 지점", "longTermBranch" to "장기 구분 이름").forEach { (key, title) -> WebField(title, fields[key].orEmpty(), { fields[key] = it }, editable && key != "longTermBranch") }
            Text("기존 차량의 지점과 배열 순서는 변경하지 않습니다.", color = WebSub, fontSize = 11.sp)
            Text("AI 키 (관리자 전용)", modifier = Modifier.padding(top = 18.dp))
            listOf("geminiKey" to "Gemini 키", "grokKey" to "Grok 키").forEach { (key, title) ->
                OutlinedTextField(fields[key].orEmpty(), { fields[key] = it }, label = { Text(title) }, enabled = editable, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
            Text("직원 위치 공유 시간", modifier = Modifier.padding(top = 18.dp))
            WebField("업무 시작 (HH:mm)", fields["start"].orEmpty(), { fields["start"] = it }, editable)
            WebField("업무 종료 (HH:mm)", fields["end"].orEmpty(), { fields["end"] = it }, editable)
            Text("관리자·토요일·일요일·공휴일 제외. 최초 동의는 유지됩니다.", color = WebSub, fontSize = 11.sp)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = { model.saveSettings(listOf("name", "homeBranch", "longTermBranch").associateWith { fields[it].orEmpty().trim() },
                mapOf("geminiKey" to fields["geminiKey"].orEmpty().trim(), "grokKey" to fields["grokKey"].orEmpty().trim(), "provider" to "gemini", "updatedAt" to Instant.now().toString()),
                fields["start"].orEmpty(), fields["end"].orEmpty()) { if (it) close() } }, enabled = editable, modifier = Modifier.weight(1f)) { Text("저장") } }
    }
}

@Composable internal fun FleetQuickApps(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var editKey by remember { mutableStateOf<String?>(null) }; var editing by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }; var url by remember { mutableStateOf("") }; var removing by remember { mutableStateOf<String?>(null) }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Row { Text("자주 쓰는 앱", fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (state.session?.isAdmin == true) TextButton(onClick = { editKey = null; label = ""; url = ""; editing = true }) { Text("추가") } }
            val current = state.quickApps.keys().asSequence().mapNotNull { key -> state.quickApps.optJSONObject(key)?.let { key to it } }.sortedBy { it.second.optString("label") }.toList()
            val legacy = state.legacyQuickApp?.takeIf { it.optString("label").isNotBlank() && it.optString("url").isNotBlank() && current.none { entry -> entry.second.optString("url") == it.optString("url") } }
            val apps = (legacy?.let { listOf("_legacy" to it) } ?: emptyList()) + current
            apps.forEach { (key, app) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    TextButton(onClick = {
                        val uri = Uri.parse(app.optString("url"))
                        if (uri.scheme in setOf("https", "http")) context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }, modifier = Modifier.weight(1f)) { Text(app.optString("label")) }
                    if (state.session?.isAdmin == true) {
                        TextButton(onClick = { editKey = key; label = app.optString("label"); url = app.optString("url"); editing = true }) { Text("수정", fontSize = 11.sp) }
                        TextButton(onClick = { removing = key }) { Text("삭제", fontSize = 11.sp) }
                    }
                }
            }
            if (apps.isEmpty()) Text("등록된 앱이 없습니다.", color = WebSub, modifier = Modifier.padding(top = 20.dp))
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("자주 쓰는 앱") }, text = { Column {
        WebField("이름 (12자 이내)", label, { label = it }, !state.sending); WebField("웹 주소", url, { url = it }, !state.sending)
        if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
    } }, confirmButton = { TextButton(onClick = { model.saveQuickApp(editKey, label, url) { if (it) editing = false } }, enabled = !state.sending && !state.cached) { Text("저장") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("취소") } })
    removing?.let { key -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("앱 삭제") }, text = { Text("모든 직원의 자주 쓰는 앱 목록에서 삭제할까요?") }, confirmButton = { TextButton(onClick = { model.deleteQuickApp(key); removing = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("취소") } }) }
}
