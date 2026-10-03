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
import kr.statusboard.core.FleetSales
import kr.statusboard.core.FleetVehicleDocuments
import java.util.UUID

@Composable internal fun FleetVehicleEditor(state: FleetUiState, vehicle: FleetVehicle, model: FleetViewModel, close: () -> Unit) {
    val values = remember(vehicle.plate) { mutableStateMapOf<String, String>().apply {
        listOf("type", "startDate", "returnDate", "status", "note", "extra", "regDate", "ageExpireDate", "asYears", "insuranceDate", "inspectionType", "inspectionDate", "amount", "payDay", "customerName", "customerPhone")
            .forEach { this[it] = vehicle.rawFields[it]?.toString().orEmpty() }
    } }
    var docs by remember { mutableStateOf(false) }
    var extraEditable by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var deposit by remember { mutableStateOf(vehicle.rawFields["depositPaid"] == true) }
    var inspectionDone by remember { mutableStateOf(vehicle.rawFields["inspectionDone"] == true) }
    var documentAction by remember { mutableStateOf<String?>(null) }
    var documentError by remember { mutableStateOf("") }
    val editId = remember(vehicle.plate) { UUID.randomUUID().toString() }
    var pendingExtension by remember { mutableStateOf<Map<String, Any?>?>(null) }
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
                OutlinedButton(onClick = { types = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) { Text(type.ifBlank { "선택 안함" }) }
                DropdownMenu(expanded = types, onDismissRequest = { types = false }) {
                    listOf("", "보험", "일반", "서비스", state.longBranch).forEach { option -> DropdownMenuItem(text = { Text(option.ifBlank { "선택 안함" }) }, onClick = { values["type"] = option; types = false }) }
                }
            }
            WebField("대여일 (입력하면 그날부터 자동으로 일수 카운팅)", values["startDate"].orEmpty(), { values["startDate"] = it }, editable)
            if (general) WebField("반납일자", values["returnDate"].orEmpty(), { values["returnDate"] = it }, editable)
            WebField("상태 (자동 일수 카운팅 중이 아닐 때 사용)", values["status"].orEmpty(), { values["status"] = it }, editable)
            Row { listOf("운행중", "대기", "준비중", "차고지").forEach { status -> TextButton(onClick = { values["status"] = status }, enabled = editable, modifier = Modifier.weight(1f)) { Text(status, fontSize = 11.sp) } } }
            WebField("비고 (담당자 / 반납예정일 등)", values["note"].orEmpty(), { values["note"] = it }, editable)
            Row { Text("추가정보 (자차 / 연령 / 특약 등)", color = WebSub, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { extraEditable = !extraEditable }, enabled = editable) { Text("✎ 수정", fontSize = 11.sp) } }
            OutlinedTextField(values["extra"].orEmpty(), { values["extra"] = it }, enabled = extraEditable && editable, modifier = Modifier.fillMaxWidth())
            if (general) Row { Checkbox(deposit, { deposit = it }, enabled = editable); Text(if (deposit) "입금완료" else "미입금 상태 (체크하면 입금완료로 바뀌어요)", fontSize = 13.sp) }
            TextButton(onClick = { docs = !docs }, modifier = Modifier.fillMaxWidth()) { Text("📋 보험 · 검사 · 차령 관리 ${if (docs) "▾" else "▸"}") }
            if (docs) listOf(field("최초등록일", "regDate"), field("차령 만료일", "ageExpireDate"), field("A/S 기간 (년)", "asYears"), field("보험 갱신일자", "insuranceDate"),
                field("검사종류 (일반 / 연장)", "inspectionType"), field("검사일자", "inspectionDate")).forEach { (label, key) -> WebField(label, values[key].orEmpty(), { values[key] = it }, editable) }
            if (docs) {
                Row { Checkbox(inspectionDone, { if (it) documentAction = "검사 완료" else inspectionDone = false }, enabled = editable); Text("검사 완료", fontSize = 13.sp) }
                Row {
                    TextButton(onClick = { documentAction = "보험 갱신 완료" }, enabled = editable) { Text("보험 갱신 완료", fontSize = 11.sp) }
                    TextButton(onClick = { documentAction = "A/S 확인" }, enabled = editable) { Text("A/S 확인", fontSize = 11.sp) }
                }
                TextButton(onClick = {
                    FleetVehicleDocuments.defaults(vehicle.rawFields + values).forEach { (key, value) -> values[key] = value.toString() }
                }, enabled = editable) { Text("최초등록일 기준 날짜 계산", fontSize = 11.sp) }
                if (documentError.isNotBlank()) Text(documentError, color = WebSub, fontSize = 12.sp)
            }
            if (general || long) WebField("금액 (만원)", values["amount"].orEmpty(), { values["amount"] = it }, editable)
            if (long) WebField("결제일 (매월 며칠)", values["payDay"].orEmpty(), { values["payDay"] = it }, editable)
            if (general || long) {
                WebField("고객명", values["customerName"].orEmpty(), { values["customerName"] = it }, editable)
                WebField("고객 전화번호", values["customerPhone"].orEmpty(), { values["customerPhone"] = it }, editable)
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
                if (inspectionDone != (vehicle.rawFields["inspectionDone"] == true)) changed["inspectionDone"] = inspectionDone
                if (FleetSales.needsExtensionChoice(vehicle.rawFields, vehicle.rawFields + changed)) pendingExtension = changed
                else model.saveVehicle(vehicle, changed, editId) { if (it) close() }
            }, enabled = editable, colors = ButtonDefaults.buttonColors(containerColor = WebAmber, contentColor = WebPanel), modifier = Modifier.weight(1f)) { Text("저장") }
        }
    }
    documentAction?.let { action ->
        val inspection = action == "검사 완료"
        AlertDialog(onDismissRequest = { documentAction = null }, title = { Text(action) },
            text = { Text(when {
                inspection && values["inspectionType"] == "연장" -> "검사일을 1년 뒤로 변경합니다. 차령 연장은 최대 두 번까지 적용됩니다. 저장 버튼을 눌러 반영해주세요."
                inspection -> "다음 검사일을 1년 뒤로 변경합니다. 저장 버튼을 눌러 반영해주세요."
                action == "보험 갱신 완료" -> "보험 갱신일을 1년 뒤로 변경합니다. 저장 버튼을 눌러 반영해주세요."
                else -> "현재 A/S 만료일을 확인한 것으로 표시합니다. 저장 버튼을 눌러 반영해주세요."
            }) }, confirmButton = { TextButton(onClick = {
                val current = vehicle.rawFields + values
                runCatching {
                    when (action) {
                        "검사 완료" -> FleetVehicleDocuments.completeInspection(current).forEach { (key, value) ->
                            if (key == "inspectionDone") inspectionDone = value == true else values[key] = value.toString()
                        }
                        "보험 갱신 완료" -> values["insuranceDate"] = FleetVehicleDocuments.renewInsurance(current)
                        else -> values["asAckExpire"] = FleetVehicleDocuments.warrantyExpiry(current) ?: error("최초등록일을 먼저 입력해주세요.")
                    }
                }.onFailure { documentError = it.message.orEmpty() }
                documentAction = null
            }) { Text("확인") } }, dismissButton = { TextButton(onClick = { documentAction = null }) { Text("취소") } })
    }
    pendingExtension?.let { changes ->
        AlertDialog(onDismissRequest = { pendingExtension = null }, title = { Text("연장으로 기록할까요?") },
            text = { Text("금액이 ${vehicle.rawFields["amount"]}만 → ${changes["amount"]}만원으로 바뀌었어요.\n연장은 기존 매출을 유지하고 추가분을 따로 기록합니다.") },
            confirmButton = { TextButton(onClick = { pendingExtension = null; model.saveVehicle(vehicle, changes, editId, true) { if (it) close() } }) { Text("연장으로 기록") } },
            dismissButton = { Row {
                TextButton(onClick = { pendingExtension = null; model.saveVehicle(vehicle, changes, editId, false) { if (it) close() } }) { Text("금액만 고치기") }
                TextButton(onClick = { pendingExtension = null }) { Text("취소") }
            } })
    }
}
