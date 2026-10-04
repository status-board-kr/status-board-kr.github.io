package kr.statusboard.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

internal val WebPanel get() = FleetAppearance.panel
internal val WebPanel2 get() = FleetAppearance.panel2
internal val WebLine get() = FleetAppearance.line
internal val WebSub get() = FleetAppearance.sub
internal val WebAmber get() = FleetAppearance.amber

@Composable internal fun WebSheet(close: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = FleetAppearance.scheme()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.matchParentSize().clickable(onClick = close))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = maxHeight * .94f)
                .background(WebPanel, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .border(1.dp, WebLine, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).clickable { }.imePadding(), content = content)
        }
        }
    }
}

@Composable internal fun FleetChatDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current
    val bubbleWidth = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.coerceAtMost(480) - 32).dp
    DisposableEffect(state.session?.cacheKey) {
        val owner = state.session?.cacheKey
        FleetPush.visibleChatOwner = owner
        onDispose { if (FleetPush.visibleChatOwner == owner) FleetPush.visibleChatOwner = null }
    }
    DisposableEffect(Unit) { onDispose { model.recentChat() } }
    var input by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var openedPhoto by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var searchHits by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var jumpTarget by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { if (it.isNotEmpty()) photos = it }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { photos = (photos + Uri.parse(it)).take(12) }
        cameraUri = null
    }
    val source = state.chatHistory ?: state.chat
    val messages = source.keys().asSequence().mapNotNull { key -> source.optJSONObject(key)?.let { key to it } }
        .sortedBy { it.second.optString("at") }.toList()
    LaunchedEffect(messages.lastOrNull()?.first, state.session?.cacheKey) { if (state.chatHistory == null) model.markChatRead() }
    val visible = messages
    val list = rememberLazyListState()
    LaunchedEffect(messages.lastOrNull()?.first) { if (visible.isNotEmpty() && query.isBlank() && state.chatHistory == null && searchHits == null) list.animateScrollToItem(visible.size + 1) }
    LaunchedEffect(jumpTarget, state.chatHistory) { if (state.chatHistory != null) jumpTarget?.let { id ->
        val index = visible.indexOfFirst { it.first == id }; if (index >= 0) { list.animateScrollToItem(index + 2); jumpTarget = null }
    } }
    WebSheet(close) {
        LazyColumn(Modifier.weight(1f, fill = false).fillMaxWidth().padding(horizontal = 16.dp), state = list,
            contentPadding = PaddingValues(vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.fillMaxWidth()) {
            Text("💬 직원 메신저", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            OutlinedButton(onClick = {
                model.backupChat { file -> if (file != null) {
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "최근 2년 대화 백업"))
                } }
            }, enabled = !state.sending && !state.cached, contentPadding = PaddingValues(10.dp, 4.dp)) { Text("💾 백업", fontSize = 12.sp) }
            Text("최근 50개를 보여줘요 · 위로 올리면 더 불러와요 · 대화는 2년간 보관 (🔍검색·💾백업)", color = WebSub, fontSize = 12.sp)
        } }
            item {
                if (state.chatHistory != null) TextButton(onClick = { model.recentChat(); searchHits = null; query = ""; jumpTarget = null }, modifier = Modifier.fillMaxWidth()) { Text("최근 대화로 돌아가기") }
                else if (!state.noOlder) TextButton(onClick = model::loadOlderChat, enabled = !state.loadingOlder, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loadingOlder) "불러오는 중…" else "이전 대화 더 보기", fontSize = 12.sp)
                }
            }
            itemsIndexed(visible, key = { _, item -> item.first }) { index, (key, message) ->
                fun dayOf(value: org.json.JSONObject) = runCatching { java.time.Instant.parse(value.optString("at")).atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString() }.getOrDefault("")
                if (index == 0 || dayOf(message) != dayOf(visible[index - 1].second)) Text(dayOf(message), color = WebSub, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp).wrapContentWidth(Alignment.CenterHorizontally))
                val system = message.optString("type") == "system" || message.optString("uid") == "system"
                val mine = message.optString("uid") == state.session?.uid
                val edge = when { system -> androidx.compose.ui.graphics.Color(0xFF34D399); mine -> WebAmber; else -> WebLine }
                val fill = when { system -> edge.copy(alpha = .10f); mine -> edge.copy(alpha = .16f); else -> WebPanel2 }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (system) Alignment.CenterHorizontally else if (mine) Alignment.End else Alignment.Start) {
                    if (!system) Text("${state.members.optJSONObject(message.optString("uid"))?.optString("name").orEmpty().ifBlank { message.optString("email") }} · ${runCatching { java.time.Instant.parse(message.optString("at")).atZone(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.ofPattern("a h:mm", java.util.Locale.KOREAN)) }.getOrDefault(message.optString("at"))}", color = WebSub, fontSize = 11.sp)
                    Column(Modifier.widthIn(max = bubbleWidth * if (system) .92f else .85f).background(fill, RoundedCornerShape(12.dp))
                        .border(1.dp, edge, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        val ids = message.optJSONArray("photoIds")?.let { array -> (0 until array.length()).map { array.optString(it) } }
                            ?: listOfNotNull(message.optString("photoId").takeIf { it.isNotBlank() && it != "null" })
                        ids.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            row.forEach { id -> FleetChatPhoto(state, id, (if (ids.size == 1) Modifier.width(180.dp).heightIn(min = 60.dp) else Modifier.size(88.dp)).clickable { openedPhoto = id }, naturalHeight = ids.size == 1) }
                        } }
                        Text(message.optString("text"), fontSize = if (system) 13.sp else 14.sp)
                    }
                    if (mine || state.session?.isAdmin == true) TextButton(onClick = { deleting = key }, enabled = !state.sending,
                        contentPadding = PaddingValues(2.dp)) { Text("삭제", color = androidx.compose.ui.graphics.Color(0xFFF87171), fontSize = 11.sp) }
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(WebPanel).border(1.dp, WebLine).padding(16.dp, 10.dp)) {
            if (searchOpen) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { WebInput(query, { query = it; searchHits = null }, placeholder = "최근 2년 대화 검색") }
                    OutlinedButton(onClick = { model.searchChat(query) { if (it != null) searchHits = it } }, enabled = query.isNotBlank() && !state.sending && !state.cached) { Text("찾기") }
                }
                searchHits?.let { Text("검색 ${it.length()}건${if (it.length() == 100) " · 최근 100건 표시" else ""}", color = WebSub, fontSize = 11.sp) }
                searchHits?.let { result ->
                    val hits = result.keys().asSequence().mapNotNull { key -> result.optJSONObject(key)?.let { key to it } }.sortedByDescending { it.second.optString("at") }.toList()
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .4f).dp)) {
                        items(hits, key = { it.first }) { (key, message) ->
                            Column(Modifier.fillMaxWidth().clickable { model.jumpChat(key) { ok -> if (ok) { searchHits = null; searchOpen = false; query = ""; jumpTarget = key } } }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text("${message.optString("at")} · ${state.members.optJSONObject(message.optString("uid"))?.optString("name").orEmpty().ifBlank { message.optString("email") }}", color = WebSub, fontSize = 11.sp)
                                Text(message.optString("text"), fontSize = 13.sp)
                                Text("그때 대화 보기 ›", color = WebAmber, fontSize = 11.sp)
                            }
                            HorizontalDivider(color = WebLine)
                        }
                    }
                }
            }
            if (photos.isNotEmpty()) Column(Modifier.fillMaxWidth().background(WebPanel2).padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("사진 ${photos.size}장 · 아래에 명령 입력", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { photos = emptyList() }, enabled = !state.sending) { Text("✕") }
                }
                photos.take(4).chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { uri -> FleetLocalPhoto(uri) } } }
            }
            Row(Modifier.fillMaxWidth().background(WebPanel2, RoundedCornerShape(24.dp)).border(1.dp, WebLine, RoundedCornerShape(24.dp)).padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    val directory = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
                    val file = java.io.File(directory, "chat-${System.nanoTime()}.jpg")
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.photos", file)
                    cameraUri = uri.toString(); camera.launch(uri)
                }, enabled = !state.sending, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(34.dp)) { Icon(painterResource(R.drawable.chat_camera), "카메라로 찍기", tint = WebSub, modifier = Modifier.size(21.dp)) }
                TextButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.sending, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(34.dp)) { Icon(painterResource(R.drawable.chat_album), "사진 고르기", tint = WebSub, modifier = Modifier.size(21.dp)) }
                TextButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(30.dp)) { Icon(painterResource(R.drawable.chat_search), "대화 검색", tint = WebSub, modifier = Modifier.size(20.dp)) }
                androidx.compose.foundation.text.BasicTextField(input, { if (it.length <= 2000) input = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = FleetAppearance.text, fontSize = 15.sp),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(WebAmber), singleLine = true, enabled = !state.sending, modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 11.dp),
                    decorationBox = { field -> Box { if (input.isEmpty()) Text("메시지를 입력하세요", color = WebSub, fontSize = 14.sp); field() } })
                Button(onClick = {
                    val done: (Boolean) -> Unit = { ok -> if (ok) { input = ""; photos = emptyList() } }
                    if (photos.isEmpty()) model.sendText(input, done) else model.sendPhotos(input, photos, done)
                }, enabled = !state.sending && (input.isNotBlank() || photos.isNotEmpty()), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAmber, contentColor = WebPanel), contentPadding = PaddingValues(0.dp), modifier = Modifier.padding(start = 6.dp).size(40.dp)) { Icon(painterResource(R.drawable.chat_send), "보내기", tint = WebPanel, modifier = Modifier.size(19.dp)) }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 11.sp)
            OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("닫기", color = WebSub) }
        }
    }
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("메시지를 삭제할까요?") },
        confirmButton = { TextButton(onClick = { deleting = null; model.deleteChat(id) }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
    openedPhoto?.let { id -> Dialog(onDismissRequest = { openedPhoto = null }) {
        Column(Modifier.fillMaxWidth().background(WebPanel).padding(12.dp)) {
            FleetChatPhoto(state, id, Modifier.fillMaxWidth().height(400.dp), ContentScale.Fit)
            TextButton(onClick = { openedPhoto = null }) { Text("닫기") }
        }
    } }
    state.pendingPhoto?.let { pending ->
        AlertDialog(onDismissRequest = { model.choosePhotoVehicle(null) }, title = { Text("처리할 우리 차량 선택") },
            text = { Column { Text("사진은 전송됐습니다. 명령을 적용할 차량을 확인해주세요.")
                state.vehicles.filter { pending.candidates.isEmpty() || it.plate in pending.candidates }.forEach { vehicle ->
                    TextButton(onClick = { model.choosePhotoVehicle(vehicle.plate) }) { Text("${vehicle.plate} · ${vehicle.model}") }
                }
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { model.choosePhotoVehicle(null) }) { Text("명령 취소") } })
    }
}

@Composable private fun FleetLocalPhoto(uri: Uri) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 256 || options.outHeight / options.inSampleSize > 256) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
            }.getOrNull()
        }
    }
    Box(Modifier.size(64.dp).background(WebPanel), contentAlignment = Alignment.Center) { bitmap?.let { Image(it, "전송할 사진", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

@Composable private fun FleetChatPhoto(state: FleetUiState, id: String, modifier: Modifier, scale: ContentScale = ContentScale.Crop, naturalHeight: Boolean = false) {
    var bitmap by remember(state.session?.cacheKey, id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(state.session?.cacheKey, id) { mutableStateOf(false) }
    LaunchedEffect(state.session?.cacheKey, id) {
        val session = state.session ?: return@LaunchedEffect
        if (id.isBlank() || id.any { it in ".#$[]/" }) { failed = true; return@LaunchedEffect }
        try {
            check(FirebaseAuth.getInstance().currentUser?.uid == session.uid)
            val data = FirebaseDatabase.getInstance().getReference(session.path("photos/$id/data")).get().await().getValue(String::class.java) ?: error("No photo")
            val decoded = withContext(Dispatchers.Default) {
                val bytes = android.util.Base64.decode(data.substringAfter(','), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            if (FirebaseAuth.getInstance().currentUser?.uid == session.uid) bitmap = decoded
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failed = true
        }
    }
    Box(modifier.background(WebPanel2), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, contentDescription = "첨부 사진", contentScale = scale, modifier = if (naturalHeight) Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height.coerceAtLeast(1)) else Modifier.fillMaxSize()) }
            ?: Text(if (failed) "사진 확인 불가" else "사진 불러오는 중", fontSize = 10.sp, color = WebSub)
    }
}
@Composable internal fun FleetPhotoViewer(state: FleetUiState, id: String, close: () -> Unit) {
    Dialog(onDismissRequest = close) { Column(Modifier.fillMaxWidth().background(WebPanel).padding(12.dp)) {
        FleetChatPhoto(state, id, Modifier.fillMaxWidth().height(400.dp), ContentScale.Fit)
        TextButton(onClick = close) { Text("닫기") }
    } }
}
