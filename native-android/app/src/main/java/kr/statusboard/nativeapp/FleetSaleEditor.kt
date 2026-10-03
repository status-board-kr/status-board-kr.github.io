package kr.statusboard.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.LocalDate
import java.time.Instant
import java.util.UUID

@Composable internal fun FleetSaleEditor(state: FleetUiState, model: FleetViewModel, key: String, record: JSONObject, close: () -> Unit) {
    var name by remember(key) { mutableStateOf(record.optString("customerName")) }
    var paid by remember(key) { mutableStateOf(record.optBoolean("depositPaid")) }
    var deleting by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val id = remember(key) { UUID.randomUUID().toString() }; val version = remember(key) { record.optString("updatedAt").takeIf(String::isNotBlank) }
    val rows = remember(key) { mutableStateListOf<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>().apply {
        val list = record.optJSONArray("items")
        if (list != null && list.length() > 0) for (index in 0 until list.length()) {
            val item = list.getJSONObject(index); add(mutableStateMapOf<String, String>().apply { item.keys().forEach { put(it, item.optString(it)) } })
        } else add(mutableStateMapOf("kind" to "최초", "amount" to record.optString("amount"), "date" to record.optString("date"), "to" to record.optString("returnDate")))
    } }
    val enabled = !state.sending && !state.cached
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("${record.optString("plate")} 매출 수정", fontSize = 18.sp)
            WebField("고객명", name, { name = it }, enabled)
            Row { Checkbox(paid, { paid = it }, enabled = enabled); Text("입금 완료", modifier = Modifier.padding(top = 12.dp)) }
            Text(if (state.vehicles.any { it.rawFields["saleKey"] == key }) "연결된 운행 차량의 금액·입금 여부도 같이 반영됩니다." else "회수된 매출 기록만 반영됩니다.", color = WebSub, fontSize = 11.sp)
            rows.toList().forEachIndexed { index, item ->
                Text("${index + 1}. ${item["kind"].orEmpty()}", modifier = Modifier.padding(top = 14.dp))
                listOf("amount" to "금액 (만원)", "date" to "시작일 (YYYY-MM-DD)", "to" to "종료일 (YYYY-MM-DD)").forEach { (field, title) -> WebField(title, item[field].orEmpty(), { item[field] = it }, enabled) }
                if (index > 0) TextButton(onClick = { rows.remove(item) }, enabled = enabled) { Text("이 연장 항목 삭제") }
            }
            TextButton(onClick = { val last = rows.lastOrNull(); rows += mutableStateMapOf("kind" to "연장", "amount" to "0", "date" to last?.get("to").orEmpty().ifBlank { last?.get("date").orEmpty().ifBlank { LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString() } }, "to" to "", "at" to Instant.now().toString()) }, enabled = enabled && rows.size < 100) { Text("+ 연장 추가") }
            TextButton(onClick = { deleting = true }, enabled = enabled) { Text("매출 기록 삭제") }
            if (error.isNotBlank()) Text(error, color = WebSub)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub)
        }
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = { runCatching {
                val items = rows.map { item ->
                    val amount = item["amount"].orEmpty().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: error("금액을 확인해주세요.")
                    val date = LocalDate.parse(item["date"])
                    val to = item["to"]?.takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
                    require(to == null || !to.isBefore(date)) { "종료일을 확인해주세요." }
                    item.toMap() + mapOf("amount" to amount, "date" to date.toString(), "to" to to?.toString())
                }.filter { it["kind"] == "최초" || (it["amount"] as Double) > 0 }
                require(items.isNotEmpty()) { "매출 항목을 입력해주세요." }
                model.saveSale(key, id, mapOf("customerName" to name.trim(), "depositPaid" to paid, "items" to items, "amount" to items.sumOf { it["amount"] as Double }, "date" to items.first()["date"]), version) { if (it) close() }
            }.onFailure { error = it.message.orEmpty() } }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("저장") } }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("매출 기록 삭제") }, text = { Text("${record.optString("plate")} 매출 기록을 삭제할까요? 연결된 차량의 매출 연결이 해제됩니다.") },
        confirmButton = { TextButton(onClick = { model.deleteSale(key, id, version) { if (it) close() } }, enabled = enabled) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } })
}
