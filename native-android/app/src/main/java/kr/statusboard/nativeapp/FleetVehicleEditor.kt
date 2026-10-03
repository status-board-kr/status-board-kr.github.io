package kr.statusboard.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.statusboard.core.FleetVehicle

@Composable internal fun FleetVehicleEditor(state: FleetUiState, vehicle: FleetVehicle, model: FleetViewModel, close: () -> Unit) {
    val values = remember(vehicle.plate) { mutableStateMapOf<String, String>().apply {
        listOf("type", "startDate", "returnDate", "status", "note", "extra", "regDate", "ageExpireDate", "asYears", "insuranceDate", "inspectionType", "inspectionDate", "amount", "payDay", "customerName", "customerPhone")
            .forEach { this[it] = vehicle.rawFields[it]?.toString().orEmpty() }
    } }
    var docs by remember { mutableStateOf(false) }
    var extraEditable by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var deposit by remember { mutableStateOf(vehicle.rawFields["depositPaid"] == true) }
    val type = values["type"].orEmpty()
    val general = type == "일반"
    val long = type == state.longBranch
    val editable = !state.sending && !state.cached
    fun field(label: String, key: String): Pair<String, String> = label to key
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text(vehicle.plate, fontSize = 16.sp); Text(vehicle.model, color = WebSub, fontSize = 12.sp)
            Text("구분", color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
            Box {
                OutlinedButton(onClick = { types = true }, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(type.ifBlank { "선택 안함" }) }
                DropdownMenu(expanded = types, onDismissRequest = { types = false }) {
                    listOf("", "보험", "일반", "서비스", state.longBranch).forEach { option -> DropdownMenuItem(text = { Text(option.ifBlank { "선택 안함" }) }, onClick = { values["type"] = option; types = false }) }
                }
            }
            WebField("대여일 (입력하면 그날부터 자동으로 일수 카운팅)", values["startDate"].orEmpty(), { values["startDate"] = it }, false)
            if (general) WebField("반납일자", values["returnDate"].orEmpty(), { values["returnDate"] = it }, false)
            WebField("상태 (자동 일수 카운팅 중이 아닐 때 사용)", values["status"].orEmpty(), { values["status"] = it }, editable)
            Row { listOf("운행중", "대기", "준비중", "차고지").forEach { status -> TextButton(onClick = { values["status"] = status }, enabled = editable, modifier = Modifier.weight(1f)) { Text(status, fontSize = 11.sp) } } }
            WebField("비고 (담당자 / 반납예정일 등)", values["note"].orEmpty(), { values["note"] = it }, editable)
            Row { Text("추가정보 (자차 / 연령 / 특약 등)", color = WebSub, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { extraEditable = !extraEditable }, enabled = editable) { Text("✎ 수정", fontSize = 11.sp) } }
            OutlinedTextField(values["extra"].orEmpty(), { values["extra"] = it }, enabled = extraEditable && editable, modifier = Modifier.fillMaxWidth())
            if (general) Row { Checkbox(deposit, { deposit = it }, enabled = false); Text(if (deposit) "입금완료" else "미입금 상태 (체크하면 입금완료로 바뀌어요)", fontSize = 13.sp) }
            TextButton(onClick = { docs = !docs }, modifier = Modifier.fillMaxWidth()) { Text("📋 보험 · 검사 · 차령 관리 ${if (docs) "▾" else "▸"}") }
            if (docs) listOf(field("최초등록일", "regDate"), field("차령 만료일", "ageExpireDate"), field("A/S 기간 (년)", "asYears"), field("보험 갱신일자", "insuranceDate"),
                field("검사종류 (일반 / 연장)", "inspectionType"), field("검사일자", "inspectionDate")).forEach { (label, key) -> WebField(label, values[key].orEmpty(), { values[key] = it }, editable) }
            if (general || long) WebField("금액 (만원)", values["amount"].orEmpty(), { values["amount"] = it }, false)
            if (long) WebField("결제일 (매월 며칠)", values["payDay"].orEmpty(), { values["payDay"] = it }, false)
            if (general || long) {
                WebField("고객명", values["customerName"].orEmpty(), { values["customerName"] = it }, false)
                WebField("고객 전화번호", values["customerPhone"].orEmpty(), { values["customerPhone"] = it }, false)
            }
            Text("날짜는 YYYY-MM-DD 형식으로 입력해주세요.", color = WebSub, fontSize = 11.sp)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = {
                // Send only changed fields; hidden untouched values remain exactly as received.
                val changed = values.filter { (key, value) -> value != vehicle.rawFields[key]?.toString().orEmpty() }.mapValues { (key, value) ->
                    if (value.isBlank() && key in listOf("startDate", "returnDate", "regDate", "ageExpireDate", "insuranceDate", "inspectionDate", "amount", "payDay")) null else value
                }.toMutableMap<String, Any?>()
                if (deposit != (vehicle.rawFields["depositPaid"] == true)) changed["depositPaid"] = deposit
                model.saveVehicle(vehicle, changed) { if (it) close() }
            }, enabled = editable, colors = ButtonDefaults.buttonColors(containerColor = WebAmber, contentColor = WebPanel), modifier = Modifier.weight(1f)) { Text("저장") }
        }
    }
}
