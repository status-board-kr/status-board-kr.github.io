package kr.statusboard.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun FleetInquiries(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var pendingOnly by rememberSaveable { mutableStateOf(true) }
    val list = state.inquiries.keys().asSequence().mapNotNull { key -> state.inquiries.optJSONObject(key)?.let { key to it } }
        .filter { !pendingOnly || !it.second.optBoolean("contacted") }.sortedByDescending { it.second.optString("createdAt") }.toList()
    fun time(raw: String): String = runCatching { Instant.parse(raw).atZone(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault(raw)
    WebSheet(close) {
        Row(Modifier.padding(16.dp)) { Text("상담 신청", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.weight(1f))
            Checkbox(pendingOnly, { pendingOnly = it }); Text("미연락만", fontSize = 12.sp) }
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list.isEmpty()) item { Text(if (pendingOnly) "연락 안 한 상담 신청이 없어요." else "아직 상담 신청이 없어요.", color = WebSub) }
            items(list, key = { it.first }) { (key, record) ->
                var talk by rememberSaveable(key) { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Row { Text(record.optString("name").ifBlank { "(이름 없음)" }, modifier = Modifier.weight(1f))
                        Text(time(record.optString("createdAt")), color = WebSub, fontSize = 11.sp) }
                    val phone = record.optString("phone")
                    if (phone.isNotBlank()) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }) { Text(phone) }
                    val tags = listOf("kind", "car", "period").map(record::optString).filter(String::isNotBlank).joinToString(" · ")
                    if (tags.isNotBlank()) Text(tags, color = WebSub, fontSize = 12.sp)
                    if (record.optString("birth").isNotBlank()) Text("생년월일 ${record.optString("birth")}", color = WebSub, fontSize = 12.sp)
                    if (record.optString("memo").isNotBlank()) Text("요청: ${record.optString("memo")}", fontSize = 12.sp)
                    Row {
                        Text(if (record.optBoolean("contacted")) "✓ 연락 완료 · ${record.optString("contactedBy")} ${time(record.optString("contactedAt"))}" else "미연락", color = WebSub, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                        if (record.optString("transcript").isNotBlank()) TextButton(onClick = { talk = !talk }) { Text("대화 보기", fontSize = 11.sp) }
                        TextButton(onClick = { model.setInquiryContacted(key, !record.optBoolean("contacted")) }, enabled = !state.sending && !state.cached) { Text(if (record.optBoolean("contacted")) "되돌리기" else "연락 완료", fontSize = 11.sp) }
                    }
                    if (talk) Text(record.optString("transcript"), color = WebSub, fontSize = 12.sp)
                    HorizontalDivider(color = WebLine)
                }
            }
        }
        if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
}
