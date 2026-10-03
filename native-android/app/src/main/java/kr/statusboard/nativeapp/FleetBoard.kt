package kr.statusboard.nativeapp

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.statusboard.core.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun palette(type: String): Pair<Color, Color> {
    val light = !FleetAppearance.dark
    return when (type) {
        "대기" -> if (light) Color(0xFFEEF6F0) to Color(0xFF387151) else Color(0xFF203B32) to Color(0xFF88B99E)
        "준비중" -> if (light) Color(0xFFFAF4E8) to Color(0xFF8A662D) else Color(0xFF403928) to Color(0xFFC9AD79)
        "보험" -> if (light) Color(0xFFEDF3FA) to Color(0xFF36618E) else Color(0xFF20374F) to Color(0xFF85B3DE)
        "일반" -> if (light) Color(0xFFF7EFEC) to Color(0xFF8B5944) else Color(0xFF3C3030) to Color(0xFFCFB0A0)
        "장기" -> if (light) Color(0xFFF2EEF8) to Color(0xFF70548D) else Color(0xFF332D43) to Color(0xFFB7A1D1)
        else -> FleetAppearance.panel to FleetAppearance.sub
    }
}

@Composable internal fun FleetBoard(state: FleetUiState, model: FleetViewModel, open: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("전체") }
    var selected by remember { mutableStateOf<FleetVehicle?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("상태순") }
    var sortOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val connection = botConnection(state)
    val filtered = state.vehicles.filter { FleetPresentation.matches(it, filter, state.longBranch) && listOf(it.plate, it.model, it.note.orEmpty()).any { field -> field.contains(query, true) } }
    val ordered = when (sort) {
        "차량번호순" -> filtered.sortedBy { it.plate }
        "상태순" -> filtered.sortedBy { if (FleetPresentation.date(it.rawFields["startDate"]?.toString()) != null) 3 else when (it.status) { "대기" -> 1; "준비중" -> 2; else -> 4 } }
        else -> filtered.sortedBy { it.sourceIndex }
    }
    // Apply the web's selected sort first, then its stable due-date priority.
    val groups = FleetPresentation.groups(ordered, state.homeBranch, state.longBranch, today)
    Box(Modifier.fillMaxSize().background(FleetAppearance.background).windowInsetsPadding(WindowInsets.systemBars)) {
        Column(Modifier.fillMaxSize()) {
            // Match the web's sticky header instead of a second bottom navigation.
            Column(Modifier.fillMaxWidth().border(1.dp, WebLine).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeaderIcon(R.drawable.menu_history, "새로고침") { open("새로고침") }
                        HeaderIcon(R.drawable.menu_settings, "설정") { open("내 앱 설정") }
                    }
                    Column(Modifier.weight(2f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🚗 ${state.companyName} 현황판", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(today.format(java.time.format.DateTimeFormatter.ofPattern("M월 d일 E요일", java.util.Locale.KOREAN)), color = WebSub, fontSize = 11.sp)
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        if (state.session?.isAdmin == true) HeaderIcon(R.drawable.menu_history, "변경기록") { open("변경기록") }
                        HeaderIcon(R.drawable.menu_users, "직원 관리") { open(if (state.session?.isAdmin == true) "직원 관리" else "직원 목록") }
                    }
                }
                val actions = listOf("메신저" to R.drawable.menu_chat, "위치보기" to R.drawable.menu_location,
                    "상담" to R.drawable.menu_call, "카카오톡" to R.drawable.menu_kakao,
                    "자주 쓰는 앱" to R.drawable.menu_star, "일정" to R.drawable.menu_calendar,
                    "결제일 알림" to R.drawable.menu_payment) + if (state.session?.isAdmin == true) listOf("견적·계약서" to R.drawable.menu_document) else emptyList()
                BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    val columns = if (maxWidth < 333.dp) 3 else ((maxWidth.value + 6) / if (maxWidth >= 672.dp) 116 else 90).toInt().coerceAtLeast(1)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        actions.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                row.forEach { (title, icon) ->
                                    val target = if (title == "결제일 알림") "결제·미청구" else title
                                    val ink = actionColor(title)
                                    Row(Modifier.weight(1f).background(WebPanel2, RoundedCornerShape(8.dp)).border(1.dp, ink.copy(alpha = .5f), RoundedCornerShape(8.dp))
                                        .clickable { open(target) }.padding(horizontal = 3.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                        Icon(painterResource(icon), null, tint = ink, modifier = Modifier.size(15.dp))
                                        Text(title, color = ink, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 3.dp))
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FleetPresentation.filters.forEach { type ->
                        val edge = palette(type).second
                        Column(Modifier.weight(1f).background(if (filter == type) WebPanel2 else WebPanel, RoundedCornerShape(10.dp))
                            .border(1.dp, if (filter == type) edge else WebLine, RoundedCornerShape(10.dp)).clickable { filter = type }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.vehicles.count { FleetPresentation.matches(it, type, state.longBranch) }.toString(), color = edge, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(type, color = edge, fontSize = 9.sp)
                        }
                    }
                }
                Text("● 종결 봇 ${connection.label}", color = WebSub, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.End).clickable { open("종결 봇") }.padding(vertical = 5.dp))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.cached) Text("저장된 자료 · 최신 자료 확인 필요", color = WebAmber, fontSize = 11.sp)
                if (state.message.isNotBlank()) Text(state.message, color = WebAmber, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val todayItems = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }.filter { item ->
                    val date = item.optString("date").take(10)
                    val matches = if (item.optBoolean("repeat")) date.takeLast(2) == today.toString().takeLast(2) else date == today.toString()
                    val mark = item.optJSONObject("completedDates")?.opt(today.toString())
                    val done = if (item.optBoolean("repeat")) mark != null && mark != org.json.JSONObject.NULL && mark != false else item.optBoolean("done")
                    matches && !done
                }.toList()
                if (todayItems.isNotEmpty()) Text(todayItems.take(3).joinToString(" · ", prefix = "오늘 일정 · ") { it.optString("title") }, color = WebAmber, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).background(WebAmber.copy(alpha = .08f), RoundedCornerShape(7.dp)).border(1.dp, WebAmber, RoundedCornerShape(7.dp)).clickable { open("일정") }.padding(8.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = FleetAppearance.text, fontSize = 14.sp),
                        modifier = Modifier.weight(1f).background(WebPanel, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
                        decorationBox = { input -> Box { if (query.isEmpty()) Text("차량번호 / 담당자 / 메모 검색", color = WebSub, fontSize = 12.sp, maxLines = 1); input() } })
                    Box {
                        Text(sort, fontSize = 12.sp, modifier = Modifier.background(WebPanel, RoundedCornerShape(9.dp)).border(1.dp, WebLine, RoundedCornerShape(9.dp)).clickable { sortOpen = true }.padding(9.dp))
                        DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                            listOf("기본순", "차량번호순", "상태순").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { sort = value; sortOpen = false }) }
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 76.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filtered.isEmpty()) item { Text(if (state.vehicles.isEmpty()) "등록된 차량이 없습니다." else "해당하는 차량이 없습니다.", color = WebSub) }
                groups.forEach { (branch, vehicles) ->
                    item { Row(Modifier.padding(top = 3.dp)) { Text(branch, color = WebSub, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("${vehicles.size}대", color = WebSub, fontSize = 11.sp) } }
                    items(vehicles, key = { "${it.sourceIndex}:${it.plate}" }) { vehicle -> VehicleCard(vehicle, state.longBranch, today, !state.sending && !state.cached, { model.quickIdle(vehicle, java.util.UUID.randomUUID().toString()) }) { selected = vehicle } }
                }
                item { TextButton(onClick = { open("로그아웃") }, modifier = Modifier.fillMaxWidth()) { Text("로그아웃", color = WebSub) } }
            }
        }
        FloatingActionButton(onClick = { open("차량 등록") }, containerColor = WebAmber, contentColor = if (FleetAppearance.dark) Color(0xFF0F172A) else Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).size(48.dp)) { Icon(painterResource(R.drawable.menu_add), "차량 등록") }
    }
    selected?.let { vehicle -> FleetVehicleEditor(state, vehicle, model) { selected = null } }
}

@Composable private fun HeaderIcon(icon: Int, title: String, click: () -> Unit) {
    Box(Modifier.size(32.dp).background(WebPanel2, RoundedCornerShape(8.dp)).border(1.dp, WebLine, RoundedCornerShape(8.dp)).clickable(onClick = click), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), title, tint = WebSub, modifier = Modifier.size(17.dp))
    }
}

@Composable private fun VehicleCard(vehicle: FleetVehicle, longBranch: String, today: LocalDate, enabled: Boolean, idle: () -> Unit, click: () -> Unit) {
    val type = when { vehicle.status == "대기" || vehicle.status == "준비중" -> vehicle.status; vehicle.type == longBranch -> "장기"; else -> vehicle.type.orEmpty() }
    val (background, edge) = palette(type)
    val due = FleetPresentation.isDue(vehicle, today)
    val transition = rememberInfiniteTransition(label = "return-warning")
    val opacity by transition.animateFloat(1f, .45f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "return-opacity")
    val warning = if (due) Color(0xFFF87171) else edge
    var pressed by remember { mutableStateOf(false) }
    var holdTriggered by remember { mutableStateOf(false) }
    val fill by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 3000 else 150), label = "web-hold-progress")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(background)
        .drawBehind { drawRect(warning, size = androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height)); if (fill > 0) drawRect(WebAmber.copy(alpha = .22f), size = androidx.compose.ui.geometry.Size(size.width * fill, size.height)) }
        .border(if (due) 2.dp else 1.dp, if (due) warning.copy(alpha = opacity) else edge, RoundedCornerShape(12.dp))
        .pointerInput(vehicle, enabled) { detectTapGestures(onTap = { if (enabled && !holdTriggered) click() }, onPress = {
            if (enabled) {
                holdTriggered = false
                pressed = true
                val result = kotlinx.coroutines.withTimeoutOrNull(3000) { tryAwaitRelease() }
                pressed = false
                if (result == null) { holdTriggered = true; idle() }
            }
        }) }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vehicle.plate, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = .5.sp)
                if (type.isNotBlank()) Text(type, fontSize = 10.sp, color = edge, modifier = Modifier.background(edge.copy(alpha = .12f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                Text(statusLabel(vehicle, today), fontSize = 10.sp, color = if (due) warning else WebSub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(if (due) warning.copy(alpha = .18f) else WebPanel2, RoundedCornerShape(20.dp)).padding(horizontal = 7.dp, vertical = 2.dp).alpha(if (due) opacity else 1f))
            }
            Text(listOf(vehicle.rawFields["cls"]?.toString(), vehicle.model, vehicle.rawFields["fuel"]?.toString()).filterNot { it.isNullOrBlank() }.joinToString(" · "), color = WebSub, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            if (vehicle.type == "일반") ReturnLine(vehicle, today, due, opacity)
            vehicle.note?.takeIf(String::isNotBlank)?.let { Text(it, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp)) }
            vehicle.rawFields["extra"]?.toString()?.takeIf(String::isNotBlank)?.let { Text(it, color = WebSub, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)) }
            if (vehicle.type != "일반") ReturnLine(vehicle, today, due, opacity)
            FleetPresentation.warnings(vehicle, today).forEach { Text(it, color = WebAmber, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp)) }
        }
        val amount = vehicle.rawFields["amount"]?.toString().orEmpty().takeUnless { it in listOf("", "null", "0", "0.0") }
        amount?.let { Column(Modifier.padding(start = 6.dp), horizontalAlignment = Alignment.End) {
            vehicle.rawFields["customerName"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { name -> Text(name, color = WebSub, fontSize = 11.sp) }
            Text("${it.removeSuffix(".0")}만원", color = WebSub, fontSize = 11.sp)
            vehicle.rawFields["payDay"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let { day -> Text("매월 ${day.removeSuffix(".0")}일", color = WebSub, fontSize = 10.sp) }
            if (vehicle.type == "일반") Text(if (vehicle.rawFields["depositPaid"] == true) "입금완료" else "미입금", color = WebAmber, fontSize = 10.sp)
        } }
    }
}

private fun actionColor(title: String): Color = when (title) {
    "메신저" -> palette("보험").second
    "위치보기" -> if (FleetAppearance.dark) Color(0xFFF472B6) else Color(0xFF8B5944)
    "상담" -> if (FleetAppearance.dark) Color(0xFF34D399) else Color(0xFF387151)
    "일정" -> palette("장기").second
    "견적·계약서" -> if (FleetAppearance.dark) Color(0xFF38BDF8) else Color(0xFF216B83)
    "카카오톡" -> if (FleetAppearance.dark) Color(0xFFFDE047) else Color(0xFF806C18)
    else -> WebAmber
}

private fun statusLabel(vehicle: FleetVehicle, today: LocalDate): String {
    val start = FleetPresentation.date(vehicle.rawFields["startDate"]?.toString()) ?: return vehicle.status.ifBlank { "운행중" }
    val days = (ChronoUnit.DAYS.between(start, today) + 1).coerceAtLeast(1)
    val end = FleetPresentation.date(vehicle.returnDate)
    return if (end != null) { val total = (ChronoUnit.DAYS.between(start, end) + 1).coerceAtLeast(1); "${total}일중 ${days.coerceAtMost(total)}일째" } else "${days}일째"
}

@Composable private fun ReturnLine(vehicle: FleetVehicle, today: LocalDate, due: Boolean, opacity: Float) {
    FleetPresentation.date(vehicle.returnDate)?.let { date ->
        val days = ChronoUnit.DAYS.between(today, date)
        val label = when { days == 0L -> "D-day"; days > 0 -> "D-$days"; else -> "D+${-days}" }
        Text("반납일자: ${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 ($label)", fontSize = 11.sp,
            color = if (due) Color(0xFFF87171) else if (days == 1L) WebAmber else WebSub, modifier = Modifier.padding(top = 3.dp).alpha(if (due) opacity else 1f))
    }
}
