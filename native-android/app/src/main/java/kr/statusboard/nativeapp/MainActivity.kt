package kr.statusboard.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.statusboard.core.*
import java.time.LocalDate

private val Background = Color(0xFF0F172A)
private val TextColor = Color(0xFFDCE5F3)
private val Muted = Color(0xFF9CB0CA)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Color(0xFF18243A), onBackground = TextColor, onSurface = TextColor)) {
                val model: FleetViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    if (state.signedIn) FleetShell(state, model::refresh, model::logout, model::retryWooky) else Login(state, model::login)
                }
            }
        }
    }
}
@Composable private fun Login(state: FleetUiState, login: (String, String) -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("현황판", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("전용 앱 시험판 · 기존 현황판 계정으로 로그인", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
        OutlinedTextField(email, { email = it }, label = { Text("이메일") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        Button(onClick = { login(email, password); password = "" }, enabled = !state.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (state.busy) "로그인 확인 중…" else "로그인") }
        if (state.message.isNotBlank()) Text(state.message, color = Color(0xFFEAC483), modifier = Modifier.padding(top = 16.dp))
    }
}
private fun palette(type: String): Pair<Color, Color> = when (type) {
    "대기" -> Color(0xFF203B32) to Color(0xFF88B99E)
    "준비중" -> Color(0xFF403928) to Color(0xFFC9AD79)
    "보험" -> Color(0xFF20374F) to Color(0xFF85B3DE)
    "일반" -> Color(0xFF3C3030) to Color(0xFFCFB0A0)
    "장기" -> Color(0xFF332D43) to Color(0xFFB7A1D1)
    else -> Color(0xFF20374F) to Color(0xFFCFDBEC)
}
@Composable internal fun FleetBoard(state: FleetUiState, refresh: () -> Unit, logout: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("전체") }
    var selected by remember { mutableStateOf<FleetVehicle?>(null) }
    var scheduleOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val today = LocalDate.now()
    val filtered = state.vehicles.filter { FleetPresentation.matches(it, filter, state.longBranch) && listOf(it.plate, it.model, it.note.orEmpty()).any { field -> field.contains(query, ignoreCase = true) } }
    val groups = FleetPresentation.groups(filtered, state.homeBranch, state.longBranch, today)
    Column(Modifier.fillMaxSize().background(Background).padding(WindowInsets.systemBars.asPaddingValues())) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("${state.companyName} · 전용 앱 시험판", color = Muted, fontSize = 12.sp)
                Text("차량 현황", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { scheduleOpen = true }, modifier = Modifier.weight(1f)) { Text("일정") }
                    OutlinedButton(onClick = refresh, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("새로고침") }
                    OutlinedButton(onClick = logout, modifier = Modifier.weight(1f)) { Text("로그아웃") }
                }
                Text("차량·일정 조회용 시험판 · 업무 처리는 기존 앱을 이용해주세요.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.cached) Text("저장된 자료 · 최신 자료 확인 필요", color = Color(0xFFEAC483), fontSize = 12.sp)
                if (state.message.isNotBlank()) Text(state.message, color = Color(0xFFEAC483), fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    FleetPresentation.filters.forEach { type ->
                        val edge = palette(type).second
                        Column(Modifier.weight(1f).border(1.dp, edge, RoundedCornerShape(5.dp)).background(if (filter == type) Color(0xFF334156) else Color(0xFF172239), RoundedCornerShape(5.dp)).clickable { filter = type }.padding(vertical = 9.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(type, color = edge, fontSize = 10.sp)
                            Text(state.vehicles.count { FleetPresentation.matches(it, type, state.longBranch) }.toString(), color = edge, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                OutlinedTextField(query, { query = it }, placeholder = { Text("차량번호 · 차종 · 메모 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { scheduleOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("오늘 일정 확인") }
            }
            if (filtered.isEmpty()) item { Text(if (state.vehicles.isEmpty()) "등록된 차량이 없습니다." else "해당하는 차량이 없습니다.", color = Muted, modifier = Modifier.padding(16.dp)) }
            groups.forEach { (branch, vehicles) ->
                item { Text("$branch · ${vehicles.size}대", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)) }
                items(vehicles, key = { "${it.sourceIndex}:${it.plate}" }) { vehicle -> VehicleCard(vehicle, state.longBranch, today) { selected = vehicle } }
            }
        }
        Text("조회 시험판 · 기존 앱의 알림과 위젯을 계속 이용하세요.", color = Muted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().background(Color(0xFF18243A)).padding(12.dp))
    }
    selected?.let { vehicle ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(vehicle.plate) }, text = {
            Column {
                Text(vehicle.model); Text("상태: ${vehicle.status}"); Text("구분: ${vehicle.type ?: "—"}")
                Text("메모: ${vehicle.note ?: "—"}"); Text("회수일: ${vehicle.returnDate ?: "—"}")
                FleetPresentation.warnings(vehicle, today).forEach { Text(it, color = Color(0xFFEAC483)) }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("닫기") } })
    }
    if (scheduleOpen) ScheduleDialog(state, today) { scheduleOpen = false }
}
@Composable private fun VehicleCard(vehicle: FleetVehicle, longBranch: String, today: LocalDate, click: () -> Unit) {
    val type = when { vehicle.status == "대기" || vehicle.status == "준비중" -> vehicle.status; vehicle.type == longBranch -> "장기"; else -> vehicle.type.orEmpty() }
    val (background, edge) = palette(type)
    val due = FleetPresentation.isDue(vehicle, today)
    val transition = rememberInfiniteTransition(label = "return-warning")
    val opacity by transition.animateFloat(1f, .45f, infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "return-opacity")
    Column(Modifier.fillMaxWidth().border(1.dp, edge, RoundedCornerShape(8.dp)).background(background, RoundedCornerShape(8.dp)).clickable(onClick = click).padding(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(vehicle.plate, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(type, fontSize = 11.sp, color = edge, modifier = Modifier.padding(top = 5.dp))
        }
        Text(vehicle.model, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        Text(vehicle.note ?: "—", fontSize = 12.sp)
        vehicle.returnDate?.takeIf(String::isNotBlank)?.let { Text("회수일: $it", fontSize = 11.sp, color = if (due) Color(0xFFFFD8A0) else TextColor, modifier = Modifier.padding(top = 7.dp).alpha(if (due) opacity else 1f)) }
        FleetPresentation.warnings(vehicle, today).forEach { Text(it, color = Color(0xFFEAC483), fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp)) }
    }
}
@Composable internal fun ScheduleDialog(state: FleetUiState, initialDate: LocalDate, close: () -> Unit) {
    var day by remember { mutableStateOf(initialDate) }
    val dayString = day.toString()
    val schedules = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }.filter { item ->
        val date = item.optString("date")
        if (item.optBoolean("repeat")) date.takeLast(2) == dayString.takeLast(2) else date.take(10) == dayString
    }.sortedBy { it.optString("date") }.toList()
    AlertDialog(onDismissRequest = close, title = { Text("$dayString 일정") }, text = {
        Column {
            Row {
                TextButton(onClick = { day = day.minusDays(1) }) { Text("이전") }
                TextButton(onClick = { day = initialDate }) { Text("오늘") }
                TextButton(onClick = { day = day.plusDays(1) }) { Text("다음") }
            }
            if (!state.scheduleLoaded) Text("일정 자료 확인 중…")
            else if (schedules.isEmpty()) Text("등록된 일정이 없습니다.")
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(schedules) { item ->
                    val done = if (item.optBoolean("repeat")) item.optJSONObject("completedDates")?.has(dayString) == true else item.optBoolean("done")
                    Column(Modifier.padding(vertical = 9.dp)) {
                        Text((if (done) "✓ " else "") + item.optString("title", item.optString("plate", "일정")))
                        Text(item.optString("memo"), color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("닫기") } })
}
