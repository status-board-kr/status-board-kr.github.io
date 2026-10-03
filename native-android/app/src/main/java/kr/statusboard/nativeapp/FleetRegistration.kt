package kr.statusboard.nativeapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.statusboard.core.FleetVehicleDocuments
import java.util.UUID

@Composable internal fun FleetRegistration(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val fields = remember { mutableStateMapOf<String, String>("branch" to state.homeBranch, "asYears" to "3", "inspectionType" to "일반") }
    val id = remember { UUID.randomUUID().toString() }
    var documents by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }
    val enabled = !state.sending && !state.cached
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("차량 등록", fontSize = 18.sp)
            listOf("plate" to "차량번호 *", "branch" to "지점", "cls" to "종별 *", "model" to "차종", "fuel" to "연료", "extra" to "추가정보").forEach { (key, label) ->
                WebField(label, fields[key].orEmpty(), { fields[key] = it }, enabled)
            }
            Row { listOf("경형", "소형", "중형", "대형", "승합").forEach { kind -> TextButton(onClick = { fields["cls"] = kind }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(kind, fontSize = 10.sp) } } }
            TextButton(onClick = { documents = !documents }) { Text("보험 · 검사 · 차령 관리 ${if (documents) "▾" else "▸"}") }
            if (documents) {
                listOf("regDate" to "최초등록일", "ageExpireDate" to "차령 만료일", "asYears" to "A/S 기간 (년)", "insuranceDate" to "보험 갱신일", "inspectionType" to "검사종류", "inspectionDate" to "검사일자").forEach { (key, label) -> WebField(label, fields[key].orEmpty(), { fields[key] = it }, enabled) }
                TextButton(onClick = {
                    runCatching { FleetVehicleDocuments.defaults(fields.filterValues(String::isNotBlank)).forEach { (key, value) -> fields[key] = value.toString() } }.onFailure { error = "최초등록일 형식을 확인해주세요." }
                }) { Text("최초등록일 기준 날짜 계산") }
                Text("날짜: YYYY-MM-DD", color = WebSub, fontSize = 11.sp)
            }
            if (error.isNotBlank()) Text(error, color = WebSub)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        Row(Modifier.padding(16.dp)) {
            OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = {
                if (fields["plate"].isNullOrBlank() || fields["cls"] !in listOf("경형", "소형", "중형", "대형", "승합")) error = "차량번호와 종별을 확인해주세요."
                else if (fields["asYears"]?.toIntOrNull()?.let { it in 1..10 } != true) error = "A/S 기간은 1~10년입니다."
                else {
                    val values: Map<String, Any?> = fields.mapValues { (key, value) -> if (key == "asYears") value.toInt() else value.trim().ifBlank { null } }
                    model.addVehicles(listOf(values), id) { if (it) close() }
                }
            }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("등록") }
        }
    }
}
