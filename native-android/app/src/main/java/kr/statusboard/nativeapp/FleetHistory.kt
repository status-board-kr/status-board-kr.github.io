package kr.statusboard.nativeapp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable internal fun FleetHistoryDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    LaunchedEffect(state.session?.cacheKey) { model.loadHistory() }
    var restoring by remember { mutableStateOf<String?>(null) }
    val id = remember(restoring) { UUID.randomUUID().toString() }
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("변경기록", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("최근 30개 차량 저장 기록. 복원 직전의 현재 자료도 기록으로 남깁니다.", color = WebSub, fontSize = 12.sp)
            val history = state.history
            if (history == null) Text("불러오는 중…", color = WebSub)
            else history.keys().asSequence().mapNotNull { key -> history.optJSONObject(key)?.let { key to it } }.sortedByDescending { it.second.optLong("savedAt") }.forEach { (key, record) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Column(Modifier.weight(1f)) { Text(Instant.ofEpochMilli(record.optLong("savedAt")).atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime().toString().replace('T', ' '), fontSize = 13.sp); Text("차량 ${record.optLong("count")}대", color = WebSub, fontSize = 11.sp) }
                    TextButton(onClick = { restoring = key }, enabled = state.session?.isAdmin == true && !state.sending && !state.cached) { Text("복원") }
                }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
    restoring?.let { key -> AlertDialog(onDismissRequest = { restoring = null }, title = { Text("차량 기록 복원") },
        text = { Text("모든 차량 정보와 배열 순서를 선택한 시점으로 되돌릴까요? 매출·일정·종결 이력은 변경하지 않습니다. 처리 중인 요청이 있으면 복원을 진행할 수 없습니다.") },
        confirmButton = { TextButton(onClick = { model.restoreHistory(key, id) { if (it) { restoring = null; model.loadHistory() } } }, enabled = !state.sending) { Text("복원") } },
        dismissButton = { TextButton(onClick = { restoring = null }) { Text("취소") } }) }
}
