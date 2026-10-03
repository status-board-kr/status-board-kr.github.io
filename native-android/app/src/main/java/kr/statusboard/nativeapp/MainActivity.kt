package kr.statusboard.nativeapp

import android.os.Bundle
import android.content.Intent
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
    private val widgetOpen = mutableStateOf<String?>(null)
    private fun captureOpen(intent: Intent?) {
        intent?.getStringExtra("fleetOpen")?.let { widgetOpen.value = it; intent.removeExtra("fleetOpen") }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); captureOpen(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureOpen(intent)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Background, surface = Color(0xFF18243A), onBackground = TextColor, onSurface = TextColor)) {
                val model: FleetViewModel = viewModel()
                val state by model.state.collectAsStateWithLifecycle()
                Surface(Modifier.fillMaxSize()) {
                    if (state.signedIn) FleetShell(state, model::refresh, model::logout, model::retryWooky, model, widgetOpen.value) { widgetOpen.value = null }
                    else Login(state, model)
                }
            }
        }
    }
}
@Composable private fun Login(state: FleetUiState, model: FleetViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var invite by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("현황판", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("전용 앱 시험판 · 기존 현황판 계정으로 로그인", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
        Row { listOf("로그인", "초대코드 가입", "업체 만들기").forEachIndexed { index, title -> TextButton(onClick = { mode = index }, enabled = !state.busy && (!state.unassigned || index != 0), modifier = Modifier.weight(1f)) { Text(title, fontSize = 11.sp) } }
        OutlinedTextField(email, { email = it }, label = { Text("이메일") }, singleLine = true, enabled = !state.busy && !state.unassigned, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        if (!state.unassigned) OutlinedTextField(password, { password = it }, label = { Text("비밀번호") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        if (mode == 1) OutlinedTextField(invite, { invite = it.uppercase().take(6) }, label = { Text("초대코드 6자리") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        if (mode == 2) OutlinedTextField(company, { company = it }, label = { Text("업체명") }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Button(onClick = {
            when (mode) { 0 -> model.login(email, password); 1 -> model.enroll(email, password, null, invite); else -> model.enroll(email, password, company, null) }
            password = ""
        }, enabled = !state.busy && (state.unassigned || (email.isNotBlank() && password.isNotEmpty())) && (mode != 1 || invite.length == 6) && (mode != 2 || company.isNotBlank()) && (!state.unassigned || mode != 0), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (state.busy) "확인 중…" else when (mode) { 0 -> "로그인"; 1 -> "초대코드로 가입"; else -> "업체 만들기" }) }
        if (!state.unassigned) TextButton(onClick = { model.resetPassword(email) }, enabled = !state.busy && email.isNotBlank()) { Text("비밀번호 재설정", fontSize = 12.sp) }
        else TextButton(onClick = model::logout) { Text("다른 계정으로 로그인", fontSize = 12.sp) }
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
@Composable internal fun FleetBoard(state: FleetUiState, model: FleetViewModel) {
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
                val todayItems = state.schedules.keys().asSequence().mapNotNull { state.schedules.optJSONObject(it) }.filter { item ->
                    val date = item.optString("date").take(10)
                    val matches = if (item.optBoolean("repeat")) date.takeLast(2) == today.toString().takeLast(2) else date == today.toString()
                    val mark = item.optJSONObject("completedDates")?.opt(today.toString())
                    val done = if (item.optBoolean("repeat")) mark != null && mark != org.json.JSONObject.NULL && mark != false else item.optBoolean("done")
                    matches && !done
                }.toList()
                todayItems.take(3).forEach { item ->
                    Text("오늘 · ${item.optString("title")}${item.optString("memo").takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                        color = Color(0xFFF5A623), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                            .background(Color(0xFFF5A623).copy(alpha = .10f), RoundedCornerShape(7.dp))
                            .border(1.dp, Color(0xFFF5A623), RoundedCornerShape(7.dp)).clickable { scheduleOpen = true }.padding(9.dp))
                }
            }
            if (filtered.isEmpty()) item { Text(if (state.vehicles.isEmpty()) "등록된 차량이 없습니다." else "해당하는 차량이 없습니다.", color = Muted, modifier = Modifier.padding(16.dp)) }
            groups.forEach { (branch, vehicles) ->
                item { Text("$branch · ${vehicles.size}대", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)) }
                items(vehicles, key = { "${it.sourceIndex}:${it.plate}" }) { vehicle -> VehicleCard(vehicle, state.longBranch, today) { selected = vehicle } }
            }
        }
    }
    selected?.let { vehicle -> FleetVehicleEditor(state, vehicle, model) { selected = null } }
    if (scheduleOpen) FleetScheduleDialog(state, today, model) { scheduleOpen = false }
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
