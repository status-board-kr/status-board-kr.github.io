package kr.statusboard.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
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
    val members = state.members.keys().asSequence().mapNotNull { uid -> state.members.optJSONObject(uid)?.let { uid to it } }
        .sortedWith(compareBy<Pair<String, org.json.JSONObject>> { if (it.second.optString("role") == "owner") 0 else 1 }.thenBy { it.second.optString("joinedAt") }).toList()
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text(if (session.isAdmin) "직원 관리" else "직원 목록", fontSize = 16.sp)
            if (session.isAdmin) {
                Button(onClick = model::createInvite, enabled = !state.sending, modifier = Modifier.padding(top = 14.dp)) { Text("초대코드 새로 만들기") }
                if (state.inviteCode.isNotBlank()) Row {
                    Text(state.inviteCode, modifier = Modifier.padding(12.dp))
                    TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("직원 초대코드", state.inviteCode)) }) { Text("복사") }
                }
            }
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
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
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
