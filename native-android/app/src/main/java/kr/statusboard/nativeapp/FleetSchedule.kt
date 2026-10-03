package kr.statusboard.nativeapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import org.json.JSONObject

@Composable internal fun FleetScheduleDialog(state: FleetUiState, initialDate: LocalDate, model: FleetViewModel, close: () -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(initialDate)) }
    var day by remember { mutableStateOf<LocalDate?>(initialDate) }
    var form by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(initialDate.toString()) }
    var memo by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val records = state.schedules.keys().asSequence().mapNotNull { key -> state.schedules.optJSONObject(key)?.let { key to it } }.toList()
    val items = records.filter { (_, item) -> day == null || if (item.optBoolean("repeat")) item.optString("date").takeLast(2) == day!!.toString().takeLast(2) else item.optString("date").take(10) == day.toString() }.sortedBy { it.second.optString("date") }
    fun edit(key: String?, item: JSONObject?) {
        editing = key; title = item?.optString("title").orEmpty(); memo = item?.optString("memo").orEmpty()
        date = item?.optString("date") ?: (day ?: initialDate).toString(); repeat = item?.optBoolean("repeat") == true; form = true
    }
    WebSheet(close) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp, 18.dp)) {
            Text("📅 일정 관리", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Text("날짜를 눌러 그날 일정을 보세요. 오늘/내일 일정은 상단 배너에도 표시돼요.", color = WebSub, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
                Text("${month.year}년 ${month.monthValue}월", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
            }
            Row { listOf("일", "월", "화", "수", "목", "금", "토").forEach { Text(it, color = WebSub, fontSize = 12.sp, modifier = Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally)) } }
            val offset = month.atDay(1).dayOfWeek.value % 7
            val cells = (0 until offset).map { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (week + List(7 - week.size) { null }).forEach { cell ->
                        val marked = cell != null && records.any { (_, item) -> if (item.optBoolean("repeat")) item.optString("date").takeLast(2) == cell.toString().takeLast(2) else item.optString("date").take(10) == cell.toString() }
                        Column(Modifier.weight(1f).height(49.dp).background(if (cell == null) WebPanel else if (cell == day) WebAmber else WebPanel2, RoundedCornerShape(8.dp))
                            .border(1.dp, if (cell == LocalDate.now() && cell != day) WebAmber else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable(enabled = cell != null) { day = cell }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            if (cell != null) { Text(cell.dayOfMonth.toString(), fontSize = 17.sp, color = if (cell == day) WebPanel else if (cell == LocalDate.now()) WebAmber else FleetAppearance.text)
                                if (marked) Box(Modifier.padding(top = 2.dp).size(4.dp).background(if (cell == day) WebPanel else androidx.compose.ui.graphics.Color(0xFF5B9DFF), RoundedCornerShape(50))) }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(day?.let { "${it.monthValue}월 ${it.dayOfMonth}일 일정" } ?: "등록된 일정", color = WebSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { day = null }) { Text("전체 일정 보기", fontSize = 12.sp) }
            }
            if (!state.scheduleLoaded) Text("일정 자료 확인 중…", color = WebSub)
            else if (items.isEmpty()) Text("이 날짜엔 등록된 일정이 없습니다", color = WebSub, modifier = Modifier.padding(20.dp))
            items.forEach { (key, item) ->
                val effective = day?.toString() ?: if (!item.optBoolean("repeat")) item.optString("date") else null
                val marker = effective?.let { item.optJSONObject("completedDates")?.opt(it) }
                val done = if (item.optBoolean("repeat")) marker != null && marker != JSONObject.NULL && marker != false else item.optBoolean("done")
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).alpha(if (done) .5f else 1f).background(WebPanel2, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp)).padding(12.dp, 10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(runCatching { LocalDate.parse(item.optString("date")).format(java.time.format.DateTimeFormatter.ofPattern("MM월 dd일")) }.getOrDefault(item.optString("date")) + if (item.optBoolean("repeat")) " · 매월 반복" else "", color = WebAmber, fontSize = 12.sp)
                        Text(item.optString("title"), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                        if (item.optString("type") == "일반") Text(if (item.optBoolean("depositPaid")) "💰 입금완료" else "⚠️ 미입금", color = if (item.optBoolean("depositPaid")) androidx.compose.ui.graphics.Color(0xFF34D399) else androidx.compose.ui.graphics.Color(0xFFF87171), fontSize = 10.sp)
                        if (item.optString("memo").isNotBlank()) Text(item.optString("memo"), color = WebSub, fontSize = 12.sp)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("완료", fontSize = 11.sp, color = WebSub)
                            Switch(done, { effective?.let { model.toggleSchedule(key, it) } }, enabled = effective != null && !state.sending && !state.cached) }
                        Row { TextButton(onClick = { edit(key, item) }) { Text("수정", fontSize = 12.sp) }
                            TextButton(onClick = { deleting = key }) { Text("삭제", fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFF87171)) } }
                    }
                }
            }
            OutlinedButton(onClick = { if (form) form = false else edit(null, null) }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text(if (form) "- 새 일정 추가 닫기" else "+ 새 일정 추가") }
            if (form) {
                WebField("제목", title, { title = it }); WebField("날짜 (YYYY-MM-DD)", date, { date = it })
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(repeat, { repeat = it }); Text("매월 같은 날짜에 반복", fontSize = 13.sp) }
                WebField("메모 (선택)", memo, { memo = it })
                Button(onClick = { model.saveSchedule(editing, title, date, repeat, memo) { if (it) form = false } }, enabled = !state.sending && !state.cached && title.isNotBlank() && runCatching { LocalDate.parse(date) }.isSuccess,
                    colors = ButtonDefaults.buttonColors(containerColor = WebAmber, contentColor = WebPanel), modifier = Modifier.fillMaxWidth()) { Text(if (editing == null) "일정 추가" else "변경 저장") }
            }
            if (state.message.isNotBlank()) Text(state.message, color = WebSub, fontSize = 12.sp)
        }
        OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth().padding(16.dp, 10.dp)) { Text("닫기") }
    }
    deleting?.let { key -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("일정 삭제") }, text = { Text("삭제하면 되돌릴 수 없습니다. 삭제할까요?") },
        confirmButton = { TextButton(onClick = { model.deleteSchedule(key); deleting = null }, enabled = !state.sending && !state.cached) { Text("삭제") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}

@Composable internal fun WebField(label: String, value: String, change: (String) -> Unit, enabled: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDate = listOf("날짜", "일자", "최초등록일", "만료일", "시작일", "종료일", "대여일", "YYYY-MM-DD").any { it in label }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = WebSub, fontSize = 12.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
    if (isDate) Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { WebInput(value, change, enabled) }
        TextButton(onClick = {
            val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            android.app.DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, enabled = enabled, modifier = Modifier.width(36.dp), contentPadding = PaddingValues(4.dp)) { Text("📅", fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
    }
    else {
        WebInput(value, change, enabled)
    }
}
