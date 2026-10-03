package kr.statusboard.nativeapp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kr.statusboard.core.FleetImport
import kr.statusboard.core.FleetVehicleDocuments
import java.util.UUID

@Composable internal fun FleetBulkRegistration(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    val rows = remember { mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>() }
    var reading by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf(state.homeBranch) }; var id by remember { mutableStateOf(UUID.randomUUID().toString()) }
    fun replace(values: List<Map<String, String>>) {
        rows.clear(); values.forEach { rows += mutableStateMapOf<String, String>().apply { putAll(it) } }; id = UUID.randomUUID().toString()
        error = if (values.isEmpty()) "차량번호를 찾지 못했습니다." else ""
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) {
        reading = true; error = ""; scope.launch {
            try { replace(FleetImportFile.read(context, uri)) }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "파일을 읽지 못했습니다." }
            finally { reading = false }
        }
    } }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(12)) { uris -> if (uris.isNotEmpty()) {
        model.readRegistration(uris) { result -> if (result != null) replace(result) }
    } }
    val enabled = !reading && !state.sending && !state.cached
    val existing = state.vehicles.map { it.plate }.toSet()
    val addable = rows.filter { it["plate"] !in existing }
    WebSheet(close) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("차량 일괄 등록", fontSize = 18.sp)
            Row { OutlinedButton(onClick = { file.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("엑셀·CSV", fontSize = 12.sp) }
                OutlinedButton(onClick = { photos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("등록증 사진", fontSize = 12.sp) } }
            Text("사진은 회사에 설정된 AI로 읽고, 아래 내용을 확인한 뒤 등록합니다.", color = WebSub, fontSize = 11.sp)
            WebField("등록 지점", branch, { branch = it }, enabled)
            Text(if (reading || state.sending) "처리 중…" else "찾은 차량 ${rows.size}대 · 추가 ${addable.size}대 · 중복 ${rows.size - addable.size}대", color = WebSub, fontSize = 12.sp)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            itemsIndexed(rows) { index, row ->
                val duplicate = row["plate"] in existing
                Row { Text("${index + 1}. ${row["plate"]}${if (duplicate) " · 이미 등록됨" else ""}", modifier = Modifier.weight(1f))
                    TextButton(onClick = { rows.remove(row) }, enabled = enabled) { Text("제외", fontSize = 11.sp) } }
                if (!duplicate) {
                    listOf("plate" to "차량번호", "model" to "차종", "fuel" to "연료", "regDate" to "최초등록일 (YYYY-MM-DD)").forEach { (key, title) -> WebField(title, row[key].orEmpty(), { row[key] = it }, enabled) }
                    var expanded by remember(row) { mutableStateOf(false) }
                    Box { OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(row["cls"].orEmpty().ifBlank { "종별 선택" }) }
                        DropdownMenu(expanded, { expanded = false }) { FleetImport.classes.forEach { kind -> DropdownMenuItem(text = { Text(kind) }, onClick = { row["cls"] = kind; expanded = false }) } } }
                }
                HorizontalDivider(color = WebLine, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        if (error.isNotBlank()) Text(error, color = WebSub, modifier = Modifier.padding(horizontal = 16.dp))
        if (state.message.isNotBlank()) Text(state.message, color = WebSub, modifier = Modifier.padding(horizontal = 16.dp), fontSize = 12.sp)
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, enabled = enabled, modifier = Modifier.weight(1f)) { Text("닫기") }
            Button(onClick = {
                runCatching {
                    require(addable.isNotEmpty() && addable.all { FleetImport.plate(it["plate"].orEmpty()) == it["plate"] && it["cls"] in FleetImport.classes }) { "차량번호와 종별을 확인해주세요." }
                    val values = addable.map { row ->
                        val raw: Map<String, Any?> = row.filterValues(String::isNotBlank) + mapOf("branch" to branch.trim(), "asYears" to 3, "inspectionType" to "일반")
                        raw + FleetVehicleDocuments.defaults(raw)
                    }
                    model.addVehicles(values, id) { if (it) close() }
                }.onFailure { error = it.message.orEmpty() }
            }, enabled = enabled && addable.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("${addable.size}대 등록") } }
    }
}
