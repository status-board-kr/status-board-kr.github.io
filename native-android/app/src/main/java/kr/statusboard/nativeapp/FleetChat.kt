package kr.statusboard.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val WebPanel = androidx.compose.ui.graphics.Color(0xFF16213A)
internal val WebPanel2 = androidx.compose.ui.graphics.Color(0xFF1C2947)
internal val WebLine = androidx.compose.ui.graphics.Color(0xFF2A3757)
internal val WebSub = androidx.compose.ui.graphics.Color(0xFF8B96B8)
internal val WebAmber = androidx.compose.ui.graphics.Color(0xFFF5A623)

@Composable internal fun WebSheet(close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().widthIn(max = 480.dp).fillMaxHeight(.94f)
                .background(WebPanel, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .border(1.dp, WebLine, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).imePadding(), content = content)
        }
    }
}

@Composable internal fun FleetChatDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { if (it.isNotEmpty()) photos = it }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        // Keep camera output in app-private storage; never upload until the user presses send.
        if (bitmap != null) {
            val file = java.io.File(context.cacheDir, "chat-${System.nanoTime()}.jpg")
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            photos = photos + Uri.fromFile(file)
        }
    }
    val messages = state.chat.keys().asSequence().mapNotNull { key -> state.chat.optJSONObject(key)?.let { key to it } }
        .sortedBy { it.second.optString("at") }.toList()
    val visible = if (query.isBlank()) messages else messages.filter { it.second.optString("text").contains(query, true) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.first) { if (visible.isNotEmpty() && query.isBlank()) list.animateScrollToItem(visible.lastIndex) }
    WebSheet(close) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
            Text("💬 직원 메신저", fontSize = 16.sp)
            OutlinedButton(onClick = {
                val text = messages.joinToString("\n\n") { (_, m) -> "${m.optString("at")} · ${m.optString("email")}\n${m.optString("text")}" }
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "현재 불러온 대화 백업"))
            }, contentPadding = PaddingValues(10.dp, 4.dp)) { Text("💾 백업", fontSize = 12.sp) }
            Text("최근 50개를 보여줘요 · 검색·백업은 현재 불러온 대화 기준", color = WebSub, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = list,
            contentPadding = PaddingValues(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(visible, key = { it.first }) { (_, message) ->
                val system = message.optString("type") == "system" || message.optString("uid") == "system"
                val mine = message.optString("uid") == state.session?.uid
                val edge = when { system -> androidx.compose.ui.graphics.Color(0xFF34D399); mine -> WebAmber; else -> WebLine }
                val fill = when { system -> edge.copy(alpha = .10f); mine -> edge.copy(alpha = .16f); else -> WebPanel2 }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (system) Alignment.CenterHorizontally else if (mine) Alignment.End else Alignment.Start) {
                    if (!system) Text("${message.optString("email")} · ${message.optString("at").take(16).replace('T', ' ')}", color = WebSub, fontSize = 11.sp)
                    Column(Modifier.fillMaxWidth(if (system) .92f else .85f).background(fill, RoundedCornerShape(12.dp))
                        .border(1.dp, edge, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        if (message.has("photoId") || message.optJSONArray("photoIds")?.length()?.let { it > 0 } == true)
                            Text("📷 첨부 사진 ${message.optJSONArray("photoIds")?.length() ?: 1}장", color = WebSub, fontSize = 12.sp)
                        Text(message.optString("text"), fontSize = if (system) 13.sp else 14.sp)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(WebPanel).border(1.dp, WebLine).padding(16.dp, 10.dp)) {
            if (searchOpen) OutlinedTextField(query, { query = it }, placeholder = { Text("검색어 (현재 불러온 대화)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (photos.isNotEmpty()) Row(Modifier.fillMaxWidth().background(WebPanel2).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("사진 ${photos.size}장 · 아래에 명령 입력", fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { photos = emptyList() }, enabled = !state.sending) { Text("✕") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { camera.launch(null) }, enabled = !state.sending, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(34.dp)) { Text("📷") }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.sending, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(34.dp)) { Text("▧") }
                TextButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(30.dp)) { Text("⌕") }
                OutlinedTextField(input, { if (it.length <= 2000) input = it }, placeholder = { Text("메시지를 입력하세요", fontSize = 12.sp) },
                    singleLine = true, enabled = !state.sending, shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f))
                Button(onClick = {
                    val done: (Boolean) -> Unit = { ok -> if (ok) { input = ""; photos = emptyList() } }
                    if (photos.isEmpty()) model.sendText(input, done) else model.sendPhotos(input, photos, done)
                }, enabled = !state.sending && (input.isNotBlank() || photos.isNotEmpty()), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAmber, contentColor = WebPanel), contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(start = 6.dp).size(40.dp)) { Text("➤") }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 11.sp)
            OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("닫기", color = WebSub) }
        }
    }
    state.pendingPhoto?.let { pending ->
        AlertDialog(onDismissRequest = { model.choosePhotoVehicle(null) }, title = { Text("처리할 우리 차량 선택") },
            text = { Column { Text("사진은 전송됐습니다. 명령을 적용할 차량을 확인해주세요.")
                state.vehicles.filter { pending.candidates.isEmpty() || it.plate in pending.candidates }.forEach { vehicle ->
                    TextButton(onClick = { model.choosePhotoVehicle(vehicle.plate) }) { Text("${vehicle.plate} · ${vehicle.model}") }
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { model.choosePhotoVehicle(null) }) { Text("명령 취소") } })
    }
}
