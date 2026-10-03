package kr.statusboard.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun FleetStaffDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val session = state.session ?: return
    val context = LocalContext.current
    var naming by remember { mutableStateOf<String?>(null) }; var name by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var apps by remember { mutableStateOf(false) }
    var eraseKeys by remember { mutableStateOf(false) }
    LaunchedEffect(session.cacheKey) { if (session.isAdmin) model.loadSettings() }
    DisposableEffect(Unit) { onDispose { model.clearSettings() } }
    val ai = state.companySettings?.optJSONObject("aiSettings")
    var gemini by remember(ai?.toString()) { mutableStateOf(ai?.optString("geminiKey").orEmpty().ifBlank { if (ai?.optString("provider") == "gemini") ai.optString("key") else "" }) }
    var grok by remember(ai?.toString()) { mutableStateOf(ai?.optString("grokKey").orEmpty()) }
    var showGemini by remember { mutableStateOf(false) }; var showGrok by remember { mutableStateOf(false) }
    val members = state.members.keys().asSequence().mapNotNull { uid -> state.members.optJSONObject(uid)?.let { uid to it } }
        .sortedWith(compareBy<Pair<String, org.json.JSONObject>> { if (it.second.optString("role") == "owner") 0 else 1 }.thenBy { it.second.optString("joinedAt") }).toList()
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("👥 직원 관리", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("이 업체에 속한 직원 목록입니다. 새 직원은 초대코드로 참여시킬 수 있어요.", color = WebSub, fontSize = 12.sp)
            members.forEach { (uid, member) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Text("${member.optString("name").ifBlank { "(이름 없음)" }} · ${if (member.optString("role") == "owner") "관리자" else "직원"}${if (uid == session.uid) " (나)" else ""}", fontSize = 14.sp)
                    Text(member.optString("email"), color = WebSub, fontSize = 11.sp)
                    Row {
                        if (session.isAdmin || uid == session.uid) TextButton(onClick = { naming = uid; name = member.optString("name") }, enabled = !state.sending) { Text("✏ 이름", fontSize = 11.sp) }
                        if (session.isAdmin && uid != session.uid) {
                            val role = if (member.optString("role") == "owner") "staff" else "owner"
                            TextButton(onClick = { confirmation = uid to role }, enabled = !state.sending) { Text(if (role == "owner") "관리자로" else "관리자 해제", fontSize = 11.sp) }
                            TextButton(onClick = { confirmation = uid to "remove" }, enabled = !state.sending) { Text("내보내기", fontSize = 11.sp) }
                        }
                    }
                    HorizontalDivider(color = WebLine)
                }
            }
            if (members.isEmpty()) Text("등록된 직원이 없습니다.", color = WebSub, modifier = Modifier.padding(vertical = 20.dp))
            if (session.isAdmin) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp).background(WebPanel2, RoundedCornerShape(12.dp)).border(1.dp, WebLine, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Text("🤖 AI 키 (관리자만 보여요)", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(if (gemini.isBlank() && grok.isBlank()) "키 없음" else "✅ 켜짐", color = WebSub, fontSize = 11.sp, modifier = Modifier.padding(bottom = 10.dp))
                    listOf("Gemini 키" to true, "Grok 키 (없어도 돼요 · 예비용)" to false).forEach { (title, primary) ->
                        Text(title, color = WebSub, fontSize = 12.sp)
                        Row(Modifier.padding(top = 4.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.weight(1f)) { WebInput(if (primary) gemini else grok, { if (primary) gemini = it else grok = it }, !state.sending && state.companySettings != null,
                                visualTransformation = if (if (primary) showGemini else showGrok) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation()) }
                            OutlinedButton(onClick = { if (primary) showGemini = !showGemini else showGrok = !showGrok }, contentPadding = PaddingValues(10.dp)) { Text("👁") }
                        }
                    }
                    Button(onClick = { if (gemini.isBlank() && grok.isBlank()) eraseKeys = true else model.saveAiKeys(gemini, grok) {} }, enabled = !state.sending && !state.cached && state.companySettings != null, modifier = Modifier.fillMaxWidth()) { Text("저장") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("Gemini 키 받기 (무료)", fontSize = 11.sp) }
                        OutlinedButton(onClick = { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://console.x.ai"))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(8.dp)) { Text("Grok 키 받기", fontSize = 11.sp) }
                    }
                    Text("여기 한 번 넣으면 모든 직원 폰의 사진 자동 인식과 홈페이지 AI 상담에 같이 쓰여요.", color = WebSub, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Column(Modifier.fillMaxWidth().padding(top = 16.dp).background(WebPanel2, RoundedCornerShape(12.dp)).border(1.dp, WebLine, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Row { Text("⭐ 자주 쓰는 앱", fontSize = 13.sp, modifier = Modifier.weight(1f)); OutlinedButton(onClick = { apps = true }) { Text("관리", fontSize = 12.sp) } }
                    Text("화면 위 ⭐ 버튼에 앱·사이트를 걸어둘 수 있어요. 모든 직원에게 똑같이 적용됩니다.", color = WebSub, fontSize = 11.sp)
                }
                HorizontalDivider(color = WebLine, modifier = Modifier.padding(top = 16.dp, bottom = 14.dp))
                Text("새 직원 초대", color = WebSub, fontSize = 13.sp)
                Text("초대코드를 만들어서 직원에게 알려주세요. 직원이 앱에서 ‘초대코드를 받았어요’를 눌러 가입하면 자동으로 이 업체에 합류합니다.", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Button(onClick = model::createInvite, enabled = !state.sending && !state.cached, modifier = Modifier.fillMaxWidth()) { Text("+ 초대코드 만들기") }
                if (state.inviteCode.isNotBlank()) Row {
                    Text(state.inviteCode, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("직원 초대코드", state.inviteCode)) }) { Text("📋 복사하기") }
                }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
    if (apps) FleetQuickApps(state, model) { apps = false }
    if (eraseKeys) AlertDialog(onDismissRequest = { eraseKeys = false }, title = { Text("AI 키를 모두 지울까요?") }, text = { Text("사진 자동 인식과 홈페이지 AI 상담이 꺼져요.") },
        confirmButton = { TextButton(onClick = { eraseKeys = false; model.saveAiKeys("", "") {} }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { eraseKeys = false }) { Text("취소") } })
    naming?.let { uid -> AlertDialog(onDismissRequest = { naming = null }, title = { Text("이름 정하기") },
        text = { Column { Text("메신저와 위치 화면에 표시할 이름입니다.", fontSize = 12.sp)
            OutlinedTextField(name, { name = it.take(20) }, singleLine = true) } },
        confirmButton = { TextButton(onClick = { model.setMemberName(uid, name) { if (it) naming = null } }, enabled = !state.sending) { Text("확인") } },
        dismissButton = { TextButton(onClick = { naming = null }) { Text("취소") } }) }
    confirmation?.let { (uid, action) -> AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (action == "remove") "직원을 내보낼까요?" else "직원 권한을 변경할까요?") },
        text = { Text(if (action == "remove") "이 직원은 더 이상 업체 자료를 볼 수 없습니다." else if (action == "owner") "직원 관리·되돌리기·설정 변경 권한이 부여됩니다." else "관리자 권한을 해제합니다.") },
        confirmButton = { TextButton(onClick = { confirmation = null; if (action == "remove") model.removeMember(uid) else model.setMemberRole(uid, action) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("취소") } }) }
}
