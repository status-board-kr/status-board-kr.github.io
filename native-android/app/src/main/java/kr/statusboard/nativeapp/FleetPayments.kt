package kr.statusboard.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.statusboard.core.FleetPayments
import kr.statusboard.core.FleetSales
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@Composable internal fun FleetPaymentDialog(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val context = LocalContext.current; val clipboard = LocalClipboardManager.current
    val today = LocalDate.now(ZoneId.of("Asia/Seoul")); val month = YearMonth.from(today).toString()
    var tab by remember { mutableIntStateOf(0) }; var settings by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }; var chosenMonth by remember { mutableStateOf(month) }
    var split by remember { mutableStateOf(true) }; var unbilled by remember { mutableStateOf(false) }
    var messagePlate by remember { mutableStateOf<String?>(null) }; var messageText by remember { mutableStateOf("") }
    var deleteLog by remember { mutableStateOf<String?>(null) }
    var clearLogs by remember { mutableStateOf(false) }
    var saleEdit by remember { mutableStateOf<String?>(null) }; var manualReturn by remember { mutableStateOf(false) }
    var manualPlate by remember { mutableStateOf("") }; var manualId by remember { mutableStateOf(java.util.UUID.randomUUID().toString()) }; var manualAt by remember { mutableStateOf(java.time.Instant.now().toString()) }
    var longFilter by remember { mutableStateOf("전체") }; var returnDate by remember { mutableStateOf("") }; var logDate by remember { mutableStateOf("") }
    var returnView by remember { mutableStateOf("전체") }; var logView by remember { mutableStateOf("전체") }
    var returnPhoto by remember { mutableStateOf<String?>(null) }
    val editable = !state.cached && !state.sending
    val expanded = remember { mutableStateListOf<String>() }
    val longVehicles = state.vehicles.filter { it.type == state.longBranch && (it.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0) > 0 }
    val sentCount = longVehicles.count { state.paymentOverrides.optJSONObject(FleetPayments.key(it.plate))?.optString("lastSentMonth") == month }
    val dueCount = longVehicles.count { vehicle -> vehicle.rawFields["payDay"]?.toString()?.toIntOrNull()?.takeIf { it in 1..31 }?.let { FleetPayments.dueDate(today, it) <= today.plusDays(3) } == true && state.paymentOverrides.optJSONObject(FleetPayments.key(vehicle.plate))?.optString("lastSentMonth") != month }
    val unbilledCount = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }.count { it.optBoolean("auto") && it.optBoolean("done") && !it.optBoolean("billed") && it.optString("type") != "일반" }
    val totalSales = state.generalSales.keys().asSequence().mapNotNull { state.generalSales.optJSONObject(it) }.sumOf { sale ->
        val values = jsonMap(sale)
        @Suppress("UNCHECKED_CAST") val items = values["items"] as? List<Map<String, Any?>>
        (items ?: listOf(mapOf("amount" to values["amount"], "date" to values["date"], "to" to values["returnDate"]))).sumOf { FleetSales.monthAmount(it, YearMonth.from(today), split) }
    }
    fun share(text: String) { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "결제 안내 공유")) }
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("결제일 알림 대시보드", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("차량 현황판의 '장기' 차량을 실시간으로 불러옵니다", color = WebSub, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.cached) "● 저장된 자료" else if (state.realtimeConnected) "● 연결됨" else "● 연결 중...", color = WebSub, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text("${today.year}년 ${today.monthValue}월", color = WebSub, fontSize = 12.sp)
            }
            val summary = listOf(longVehicles.size.toString() to "장기 차량", dueCount.toString() to "임박 (D-3 이내)", sentCount.toString() to "이번 달 발송완료", unbilledCount.toString() to "청구 미완료 (회수됨)", "${"%.1f".format(totalSales)}만" to "이번 달 일반 매출", "${"%.1f".format(longVehicles.sumOf { it.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0 })}만" to "장기금액")
            summary.chunked(2).forEachIndexed { rowIndex, row -> Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEachIndexed { column, (count, label) ->
                val index = rowIndex * 2 + column
                Column(Modifier.weight(1f).background(WebPanel2, RoundedCornerShape(6.dp)).border(1.dp, WebLine, RoundedCornerShape(6.dp)).clickable(enabled = index != 5) { when(index) { 0 -> { tab = 0; longFilter = "전체" }; 1 -> { tab = 0; longFilter = "발송 필요" }; 2 -> { tab = 0; longFilter = "발송 완료" }; 3 -> { tab = 2; unbilled = true }; 4 -> tab = 1 } }.padding(14.dp, 12.dp)) {
                    Text(count, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = when(index) { 1 -> WebAmber; 2,4 -> Color(0xFF34D399); 5 -> Color(0xFFA78BCE); else -> FleetAppearance.text })
                    Text(label, color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            } } }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (tab == 0) "결제 대상 고객" else listOf("", "일반 매출", "회수 기록", "발송 기록")[tab], color = WebSub, fontSize = 14.sp, modifier = Modifier.weight(1f))
                if (tab != 0) TextButton(onClick = { tab = 0 }) { Text("← 고객 목록", fontSize = 11.sp) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { tab = 2 }, modifier = Modifier.weight(1f)) { Text("회수 기록", fontSize = 11.sp) }
                OutlinedButton(onClick = { tab = 3 }, modifier = Modifier.weight(1f)) { Text("발송 기록", fontSize = 11.sp) }
                if (state.session?.isAdmin == true) OutlinedButton(onClick = { settings = true }, modifier = Modifier.weight(1f)) { Text("메시지 · 계좌 설정", fontSize = 11.sp) }
            }
            WebField("차량번호 · 고객명 검색", query, { query = it }, true)
            when (tab) {
                0 -> {
                    val vehicles = state.vehicles.filter { it.type == state.longBranch && (it.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0) > 0 }
                    Text("장기 ${vehicles.size}대 · 발송완료 ${vehicles.count { state.paymentOverrides.optJSONObject(FleetPayments.key(it.plate))?.optString("lastSentMonth") == month }}대", color = WebSub, fontSize = 12.sp)
                    Text("장기 합계 ${"%.1f".format(vehicles.sumOf { it.rawFields["amount"]?.toString()?.toDoubleOrNull() ?: 0.0 })}만원", color = WebAmber, fontSize = 13.sp)
                    Row { listOf("전체", "발송 필요", "발송 완료").forEach { label -> TextButton(onClick = { longFilter = label }) { Text("${if (longFilter == label) "✓ " else ""}$label", fontSize = 11.sp) } } }
                    vehicles.filter { query.isBlank() || "${it.plate} ${it.rawFields["customerName"]}".contains(query, true) }.filter { vehicle ->
                        val sent = state.paymentOverrides.optJSONObject(FleetPayments.key(vehicle.plate))?.optString("lastSentMonth") == month
                        val due = vehicle.rawFields["payDay"]?.toString()?.toIntOrNull()?.takeIf { it in 1..31 }?.let { FleetPayments.dueDate(today, it) }
                        when (longFilter) { "발송 완료" -> sent; "발송 필요" -> !sent && due != null && !due.isAfter(today.plusDays(3)); else -> true }
                    }.forEach { vehicle ->
                        val override = state.paymentOverrides.optJSONObject(FleetPayments.key(vehicle.plate))
                        val values = jsonMap(override); val text = FleetPayments.message(vehicle.rawFields, values, jsonMap(state.paymentSettings), today)
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp).background(WebPanel2, RoundedCornerShape(6.dp)).border(1.dp, WebLine, RoundedCornerShape(6.dp)).padding(14.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { if (vehicle.plate in expanded) expanded.remove(vehicle.plate) else expanded.add(vehicle.plate) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${vehicle.plate} · ${vehicle.model}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text("${vehicle.rawFields["customerName"] ?: "이름 미등록"} · ${vehicle.rawFields["amount"]}만원 · ${FleetPayments.status(vehicle.rawFields, values, today)}", color = WebSub, fontSize = 12.sp)
                            }
                            Text(if (vehicle.plate in expanded) "▴" else "▾", color = WebSub)
                        }
                        if (vehicle.plate in expanded) {
                        Text(text, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                        Row { listOf("corp" to "법인 계좌", "personal" to "개인 계좌").forEach { (key, label) ->
                            TextButton(onClick = { model.savePaymentOverride(vehicle.plate, mapOf("accountType" to key)) }, enabled = editable) { Text("${if ((values["accountType"] ?: "corp") == key) "✓ " else ""}$label", fontSize = 11.sp) }
                        } }
                        Row { TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("복사") }
                            TextButton(onClick = { share(text) }) { Text("공유") }
                            TextButton(onClick = {
                                val phone = vehicle.rawFields["customerPhone"]?.toString().orEmpty().filter { it.isDigit() || it == '+' }
                                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).putExtra("sms_body", text)
                                if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) else share(text)
                            }) { Text("문자") }
                            TextButton(onClick = { messagePlate = vehicle.plate; messageText = text }) { Text("수정") }
                        }
                        val sent = override?.optString("lastSentMonth") == month
                        Row { Checkbox(sent, { model.markPaymentSent(vehicle.plate, month, text, it) }, enabled = editable); Text("발송 완료", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
                        Text("문자·공유 앱에서 실제로 보낸 뒤 발송 완료를 체크해주세요.", color = WebSub, fontSize = 10.sp)
                        }
                        }
                    }
                }
                1 -> {
                    WebField("매출 월 (YYYY-MM)", chosenMonth, { chosenMonth = it }, true)
                    Row { Checkbox(split, { split = it }); Text("대여 기간에 따라 월별 나누기", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
                    val selected = runCatching { YearMonth.parse(chosenMonth) }.getOrNull()
                    if (selected == null) Text("월 형식을 확인해주세요.", color = WebSub)
                    else {
                        val sales = state.generalSales.keys().asSequence().mapNotNull { key -> state.generalSales.optJSONObject(key)?.let { key to jsonMap(it) } }.toList()
                        fun amount(sale: Map<String, Any?>): Double {
                            @Suppress("UNCHECKED_CAST") val items = sale["items"] as? List<Map<String, Any?>>
                            return (items ?: listOf(mapOf("amount" to sale["amount"], "date" to sale["date"], "to" to sale["returnDate"]))).sumOf { FleetSales.monthAmount(it, selected, split) }
                        }
                        val rows = sales.filter { amount(it.second) > 0 && (query.isBlank() || "${it.second["plate"]} ${it.second["customerName"]}".contains(query, true)) }
                        Text("합계 ${"%.1f".format(rows.sumOf { amount(it.second) })}만원 · ${rows.size}건", color = WebAmber, fontSize = 14.sp)
                        rows.sortedByDescending { it.second["date"]?.toString() }.forEach { (key, sale) ->
                            HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text("${sale["plate"]} · ${sale["model"] ?: ""}", fontSize = 14.sp)
                            Text("${sale["customerName"] ?: ""} · ${sale["date"]} ~ ${sale["returnDate"] ?: ""}", color = WebSub, fontSize = 11.sp)
                            Text("${"%.1f".format(amount(sale))}만원", fontSize = 14.sp)
                            Row { Checkbox(sale["depositPaid"] == true, { model.setSalePaid(key, it) }, enabled = editable); Text("입금 완료", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
                            TextButton(onClick = { saleEdit = key }, enabled = editable) { Text("매출 수정 · 연장 · 삭제", fontSize = 11.sp) }
                        }
                    }
                }
                2 -> {
                    TextButton(onClick = { manualReturn = true; manualPlate = ""; manualId = java.util.UUID.randomUUID().toString(); manualAt = java.time.Instant.now().toString() }, enabled = editable) { Text("+ 수동 회수 기록 추가") }
                    Row { listOf("전체", "날짜", "차량").forEach { label -> TextButton(onClick = { returnView = label }) { Text("${if (returnView == label) "✓ " else ""}$label", fontSize = 11.sp) } } }
                    if (returnView == "날짜") WebField("회수 날짜 (YYYY-MM-DD, 비우면 전체)", returnDate, { returnDate = it }, true)
                    Row { Checkbox(unbilled, { unbilled = it }); Text("청구 미완료만 보기", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
                    val records = state.schedules.keys().asSequence().mapNotNull { key -> state.schedules.optJSONObject(key)?.let { key to it } }
                        .filter { (_, record) -> record.optBoolean("done") && (record.optBoolean("auto") || record.optBoolean("manual")) && (!unbilled || !record.optBoolean("billed")) }
                        .filter { (_, record) -> query.isBlank() || "${record.optString("plate")} ${record.optString("title")} ${record.optString("memo")}".contains(query, true) }
                        .filter { (_, record) -> returnView != "날짜" || returnDate.isBlank() || localLogDate(record.optString("doneAt")) == returnDate }
                        .sortedByDescending { it.second.optString("doneAt") }.toList()
                    Text("회수 ${records.size}건", color = WebSub, fontSize = 12.sp)
                    (if (returnView == "차량") records.sortedBy { it.second.optString("plate") } else records).forEach { (key, record) ->
                        HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text(record.optString("plate").ifBlank { record.optString("title") }, fontSize = 14.sp)
                        Text("${record.optString("type")} · ${record.optString("doneAt")}\n${record.optString("memo")}\n종료 주행거리: ${record.optString("returnKm").ifBlank { "미입력" }}", color = WebSub, fontSize = 11.sp)
                        Row { Checkbox(record.optBoolean("billed"), { model.markBilled(key, it) }, enabled = editable); Text("청구 완료", fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp)) }
                        val photo = record.optString("photoId").takeIf { it.isNotBlank() && it != "null" }
                        if (photo != null) TextButton(onClick = { returnPhoto = photo }) { Text("회수 사진 보기", fontSize = 11.sp) }
                    }
                }
                3 -> {
                    Row { listOf("전체", "날짜", "차량").forEach { label -> TextButton(onClick = { logView = label }) { Text("${if (logView == label) "✓ " else ""}$label", fontSize = 11.sp) } } }
                    if (logView == "날짜") WebField("발송 날짜 (YYYY-MM-DD, 비우면 전체)", logDate, { logDate = it }, true)
                    Text("최근 발송 기록 100건", color = WebSub, fontSize = 11.sp)
                    TextButton(onClick = { clearLogs = true }, enabled = editable && state.paymentSendLog.length() > 0) { Text("전체 발송 기록 삭제", fontSize = 11.sp) }
                    state.paymentSendLog.keys().asSequence().mapNotNull { key -> state.paymentSendLog.optJSONObject(key)?.let { key to it } }
                        .filter { query.isBlank() || "${it.second.optString("plate")} ${it.second.optString("name")}".contains(query, true) }
                        .filter { logView != "날짜" || logDate.isBlank() || localLogDate(it.second.optString("sentAt")) == logDate }
                        .sortedBy { if (logView == "차량") it.second.optString("plate") else "" }.let { list -> if (logView == "차량") list else list.sortedByDescending { it.second.optString("sentAt") } }.forEach { (key, entry) ->
                            HorizontalDivider(Modifier.padding(vertical = 12.dp)); Text("${entry.optString("plate")} · ${entry.optString("name")}", fontSize = 14.sp)
                            Text("${entry.optString("ym")} · ${entry.optString("sentAt")}\n${entry.optString("message")}", color = WebSub, fontSize = 11.sp)
                            TextButton(onClick = { deleteLog = key }, enabled = editable) { Text("기록 삭제", fontSize = 11.sp) }
                        }
                }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("닫기") }
    }
    saleEdit?.let { key -> state.generalSales.optJSONObject(key)?.let { FleetSaleEditor(state, model, key, it) { saleEdit = null } } }
    if (manualReturn) AlertDialog(onDismissRequest = { manualReturn = false }, title = { Text("수동 회수 기록") }, text = { Column {
        WebField("반납 처리된 차량번호", manualPlate, { manualPlate = it }, editable)
        Text("미청구 목록에 기록만 추가합니다.", color = WebSub, fontSize = 11.sp)
        if (state.message.isNotBlank()) Text(state.message, color = WebSub)
    } }, confirmButton = { TextButton(onClick = { model.addManualReturn(manualId, manualPlate, manualAt) { if (it) manualReturn = false } }, enabled = editable && manualPlate.isNotBlank()) { Text("기록 추가") } }, dismissButton = { TextButton(onClick = { manualReturn = false }) { Text("취소") } })
    returnPhoto?.let { id -> FleetPhotoViewer(state, id) { returnPhoto = null } }
    if (settings) FleetPaymentSettings(state, model) { settings = false }
    messagePlate?.let { plate -> AlertDialog(onDismissRequest = { messagePlate = null }, title = { Text("이번 달 안내문 수정") },
        text = { OutlinedTextField(messageText, { messageText = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { model.savePaymentOverride(plate, mapOf("customMessage" to mapOf("ym" to month, "text" to messageText.trim()))); messagePlate = null }, enabled = editable) { Text("저장") } },
        dismissButton = { TextButton(onClick = { model.savePaymentOverride(plate, mapOf("customMessage" to null)); messagePlate = null }, enabled = editable) { Text("기본 안내문으로") } }) }
    deleteLog?.let { key -> AlertDialog(onDismissRequest = { deleteLog = null }, title = { Text("발송 기록 삭제") }, text = { Text("선택한 발송 기록을 삭제할까요?") },
        confirmButton = { TextButton(onClick = { model.deletePaymentLog(key); deleteLog = null }) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleteLog = null }) { Text("취소") } }) }
    if (clearLogs) AlertDialog(onDismissRequest = { if (!state.sending) clearLogs = false }, title = { Text("전체 발송 기록 삭제") },
        text = { Column { Text("발송 기록 전체를 삭제할까요? 차량 매출과 이번 달 발송 완료 표시는 유지됩니다."); if (state.message.isNotBlank()) Text(state.message) } },
        confirmButton = { TextButton(onClick = { model.clearPaymentLogs { if (it) clearLogs = false } }, enabled = editable) { Text("전체 삭제") } },
        dismissButton = { TextButton(onClick = { clearLogs = false }, enabled = !state.sending) { Text("취소") } })
}
private fun localLogDate(raw: String): String = runCatching { java.time.Instant.parse(raw).atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString() }.getOrDefault(raw.take(10))

@Composable private fun FleetPaymentSettings(state: FleetUiState, model: FleetViewModel, close: () -> Unit) {
    val source = state.paymentSettings
    var company by remember { mutableStateOf(source.optString("company").ifBlank { state.companyName }) }
    var template by remember { mutableStateOf(source.optString("template").ifBlank { FleetPayments.TEMPLATE }) }
    val fields = remember { mutableStateMapOf<String, String>().apply {
        listOf("corp", "personal").forEach { kind -> listOf("bank", "number", "holder").forEach { field -> this["$kind-$field"] = source.optJSONObject("accounts")?.optJSONObject(kind)?.optString(field).orEmpty() } }
    } }
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("메시지 · 계좌 설정", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            WebField("회사명", company, { company = it }, !state.sending)
            WebField("메시지 템플릿", template, { template = it }, !state.sending)
            Text("{회사} {이름} {날짜} {차량정보} {계좌}를 안내문에 사용할 수 있습니다.", color = WebSub, fontSize = 11.sp)
            listOf("corp" to "법인 계좌", "personal" to "개인 계좌").forEach { (kind, title) ->
                Text(title, modifier = Modifier.padding(top = 14.dp))
                listOf("bank" to "은행", "number" to "계좌번호", "holder" to "예금주").forEach { (field, label) -> WebField(label, fields["$kind-$field"].orEmpty(), { fields["$kind-$field"] = it }, !state.sending) }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        Row(Modifier.padding(16.dp)) { OutlinedButton(onClick = close, modifier = Modifier.weight(1f)) { Text("취소") }
            Button(onClick = { model.savePaymentSettings(mapOf("company" to company.trim(), "template" to template.ifBlank { FleetPayments.TEMPLATE }, "accounts" to
                listOf("corp", "personal").associateWith { kind -> listOf("bank", "number", "holder").associateWith { fields["$kind-$it"].orEmpty().trim() } })) { if (it) close() } }, enabled = !state.sending && !state.cached, modifier = Modifier.weight(1f)) { Text("저장") } }
    }
}
