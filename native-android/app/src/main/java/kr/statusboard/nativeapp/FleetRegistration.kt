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
    var bulk by remember { mutableStateOf(false) }
    val enabled = !state.sending && !state.cached
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("차량 추가", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("새 차량 정보를 입력하세요", color = WebSub, fontSize = 12.sp)
            OutlinedButton(onClick = { bulk = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("📥 엑셀·등록증 사진으로 여러 대 한번에 등록", fontSize = 13.sp) }
            if (!documents) {
            listOf("plate" to "차량번호 *", "branch" to "구역").forEach { (key, label) ->
                WebField(label, fields[key].orEmpty(), { fields[key] = it }, enabled)
            }
            WebSelect("종별 *", fields["cls"].orEmpty(), listOf("" to "-- 선택 --") + listOf("경형", "소형", "중형", "대형", "승합").map { it to it }, { fields["cls"] = it }, enabled)
            Text("💡 차령 자동계산에 그대로 쓰여서 목록에서만 고를 수 있게 했어요.", color = WebSub, fontSize = 10.5.sp)
            listOf("model" to "차종", "fuel" to "연료", "extra" to "추가정보 (자차 / 연령 / 특약 등)").forEach { (key, label) -> WebField(label, fields[key].orEmpty(), { fields[key] = it }, enabled) }
            }
            TextButton(onClick = { documents = !documents }, modifier = Modifier.fillMaxWidth()) { Text("📋 보험 · 검사 · 차령 관리 ${if (documents) "▾" else "▸"}") }
            if (documents) {
                fun defaults(force: Boolean) {
                    runCatching { FleetVehicleDocuments.defaults(fields.filterValues(String::isNotBlank).let { if (force) it - "ageExpireDate" else it }).forEach { (key, value) -> fields[key] = value.toString() } }.onFailure { error = "최초등록일 형식을 확인해주세요." }
                }
                WebField("최초등록일 (전부 이 날짜 기준 자동계산)", fields["regDate"].orEmpty(), { fields["regDate"] = it; if (runCatching { java.time.LocalDate.parse(it) }.isSuccess) defaults(true) }, enabled)
                WebField("차령 만료일", fields["ageExpireDate"].orEmpty(), { fields["ageExpireDate"] = it }, enabled) { TextButton(onClick = { defaults(true) }, enabled = enabled) { Text("⚙ 재계산", fontSize = 11.sp) } }
                WebField("A/S 기간 (년)", fields["asYears"].orEmpty(), { fields["asYears"] = it }, enabled)
                Text("💡 대부분 3년/6만km이지만 5년짜리 등 다른 차량은 여기서 직접 바꿔주세요.", color = WebSub, fontSize = 10.5.sp)
                WebField("보험 갱신일자", fields["insuranceDate"].orEmpty(), { fields["insuranceDate"] = it }, enabled)
                Text("💡 최초등록일 입력 시 비어있으면 1년 뒤 날짜로 자동입력돼요.", color = WebSub, fontSize = 10.5.sp)
                WebSelect("검사종류", fields["inspectionType"].orEmpty(), listOf("일반" to "일반검사", "연장" to "차령연장검사"), { fields["inspectionType"] = it }, enabled)
                WebField("검사일자", fields["inspectionDate"].orEmpty(), { fields["inspectionDate"] = it }, enabled)
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
            }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("추가") }
        }
    }
    if (bulk) FleetBulkRegistration(state, model) { bulk = false }
}
