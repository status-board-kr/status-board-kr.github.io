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

@Composable internal fun FleetDocumentRates(state: FleetUiState, model: FleetViewModel, schema: JSONObject, close: () -> Unit) {
    val original = jsonMap(schema.getJSONObject("newcarRates")) + jsonMap(state.documents?.optJSONObject("_newcarRates"))
    val fields = remember { mutableStateMapOf<String, String>().apply { original.forEach { (key, value) -> put(key, value.toString()) } } }
    var error by remember { mutableStateOf("") }
    val editable = !state.sending && !state.cached && state.session?.isAdmin == true
    WebSheet(close) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("신차 렌트 · 계산 기준", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            listOf("rate" to "할부 연 금리 (%)", "months" to "할부 기간 (개월)", "down" to "내 선수금 (%)", "acq" to "취등록세 (%)", "reg" to "등록 부대비용 (원)",
                "ins" to "월 보험료 (원)", "maint" to "월 정비비 (원)", "fee" to "월 지입료 (원)", "etc" to "월 기타비용 (원)", "margin" to "월 마진 (원)", "age21" to "21세 추가 (원)", "d2" to "2안 기본 보증금 (%)", "d3" to "3안 기본 보증금 (%)").forEach { (key, title) -> WebField(title, fields[key].orEmpty(), { fields[key] = it }, editable) }
            WebField("가격표 (한 줄: 제조사 차종 연료 트림 가격(만원))", fields["cars"].orEmpty(), { fields["cars"] = it }, editable)
            Text("회사의 기존 웹 계산 기준과 같은 값을 사용합니다. 새 계약의 실제 가격은 확인 후 입력해주세요.", color = WebSub, fontSize = 11.sp)
            if (error.isNotBlank()) Text(error, color = WebSub)
            if (state.message.isNotBlank()) Text(state.message, color = WebSub)
        }
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = {
                runCatching {
                    val numeric = fields.filterKeys { it != "cars" }.mapValues { (_, raw) -> raw.replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: error("계산 기준은 0 이상의 숫자로 입력해주세요.") }
                    require((numeric["months"] ?: 0.0) > 0) { "할부 기간을 확인해주세요." }
                    require(listOf("down", "acq", "d2", "d3").all { (numeric[it] ?: 0.0) <= 100 }) { "비율은 0~100%입니다." }
                    model.saveDocumentRates(numeric + ("cars" to fields["cars"].orEmpty())) { if (it) { model.loadDocuments(); close() } }
                }.onFailure { error = it.message.orEmpty() }
            }, enabled = editable, modifier = Modifier.weight(1f)) { Text("기준 저장") } }
    }
}
