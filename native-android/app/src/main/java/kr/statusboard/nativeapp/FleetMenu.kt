package kr.statusboard.nativeapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.Instant
import kotlinx.coroutines.delay
import kr.statusboard.core.BotConnection
import kr.statusboard.core.FleetBotHealth

private val Ink = Color(0xFF1C2D44)
private val Soft = Color(0xFF8190A5)
private val Line = Color(0xFFE2E8F0)
private val Accent = Color(0xFF375C84)
private val MenuBackground = Color(0xFFF6F8FB)
private data class MenuItem(val title: String, val icon: Int)

@Composable internal fun FleetShell(state: FleetUiState, refresh: () -> Unit, logout: () -> Unit, retryWooky: () -> Unit, model: FleetViewModel) {
    var menu by rememberSaveable(state.session?.cacheKey) { mutableStateOf(false) }
    var schedule by rememberSaveable(state.session?.cacheKey) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var botOpen by remember { mutableStateOf(false) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    val screens = rememberSaveableStateHolder()
    val open: (String) -> Unit = { title ->
        when (title) {
            "차량 현황" -> menu = false
            "전체 메뉴" -> menu = true
            "일정" -> schedule = true
            "메신저" -> chatOpen = true
            "새로고침" -> refresh()
            "로그아웃" -> logout()
            "종결 봇" -> botOpen = true
            else -> pending = title
        }
    }
    BackHandler(enabled = menu && !schedule && pending == null) { menu = false }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (menu) FleetMenu(state, open)
            else screens.SaveableStateProvider("fleet") { FleetBoard(state, model) }
        }
        MaterialTheme(colorScheme = lightColorScheme(onSurface = Ink, surface = Color.White, primary = Accent)) {
            Row(Modifier.fillMaxWidth().background(Color.White).border(1.dp, Line)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(vertical = 10.dp)) {
                listOf(MenuItem("차량 현황", R.drawable.menu_board), MenuItem("일정", R.drawable.menu_calendar),
                    MenuItem("메신저", R.drawable.menu_chat), MenuItem("전체 메뉴", R.drawable.menu_more)).forEach { item ->
                    Column(Modifier.weight(1f).clickable { open(item.title) }.padding(vertical = 3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val active = if (menu) item.title == "전체 메뉴" else item.title == "차량 현황"
                        MenuIcon(item.icon, if (active) Accent else Soft, 18)
                        Text(item.title, color = if (active) Accent else Soft, fontSize = 10.sp,
                            modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
    if (schedule) FleetScheduleDialog(state, LocalDate.now(), model) { schedule = false }
    if (chatOpen) FleetChatDialog(state, model) { chatOpen = false }
    if (botOpen) {
        val connection = botConnection(state)
        val failed = state.wookyJobs.keys().asSequence().mapNotNull { state.wookyJobs.optJSONObject(it) }.count { it.optString("status") == "done" && it.optString("result") in listOf("fail", "error") }
        AlertDialog(onDismissRequest = { botOpen = false }, title = { Text("종결 봇 · ${connection.label}") },
            text = { Column {
                Text("실패 요청 ${failed}건 · PC 일꾼이 연결되어야 처리됩니다.")
                if (state.message.isNotBlank()) Text(state.message, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { if (state.session?.isAdmin == true) TextButton(onClick = retryWooky, enabled = !state.sending && failed > 0) { Text("실패 건 재시도") } },
            dismissButton = { TextButton(onClick = { botOpen = false }) { Text("닫기") } })
    }
    pending?.let { title ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(title) },
            text = { Text("이 기능은 전용 앱으로 옮기는 중입니다. 현재 업무는 기존 현황판 앱에서 이용해주세요.") },
            confirmButton = { TextButton(onClick = { pending = null }) { Text("확인") } })
    }
}
@Composable private fun botConnection(state: FleetUiState): BotConnection {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { delay(5000); now = Instant.now() } }
    val health = state.wookyJobs.keys().asSequence().mapNotNull { state.wookyJobs.optJSONObject(it)?.optJSONObject("agentHealth") }.maxByOrNull { it.optString("lastSeen") }
    return FleetBotHealth.state(health?.optString("lastSeen"), health?.optString("loginState"), now)
}

@Composable private fun MenuIcon(resource: Int, color: Color = Soft, size: Int = 19) {
    Icon(painterResource(resource), contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

@Composable private fun FleetMenu(state: FleetUiState, open: (String) -> Unit) {
    val connection = botConnection(state)
    MaterialTheme(colorScheme = lightColorScheme(background = MenuBackground, surface = Color.White, onSurface = Ink, onBackground = Ink, primary = Accent)) {
        Column(Modifier.fillMaxSize().background(MenuBackground).windowInsetsPadding(WindowInsets.statusBars)) {
            Column(Modifier.fillMaxWidth().background(Color.White).padding(19.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("전체 메뉴", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { open("차량 현황") }) { MenuIcon(R.drawable.menu_close, size = 20) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).background(Color(0xFFEFF3F8), RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                        MenuIcon(R.drawable.menu_users, Accent)
                    }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(state.companyName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${if (state.session?.isAdmin == true) "관리자" else "직원"} · ${state.homeBranch}", color = Soft, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    Text(if (state.session?.isAdmin == true) "관리자" else "직원", color = Soft, fontSize = 10.sp,
                        modifier = Modifier.background(Color(0xFFEEF3F9), RoundedCornerShape(6.dp)).padding(7.dp))
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(15.dp)) {
                Panel {
                    listOf(MenuItem("결제·미청구", R.drawable.menu_payment),
                        MenuItem("위치보기", R.drawable.menu_location), MenuItem("카카오톡", R.drawable.menu_kakao), MenuItem("자주 쓰는 앱", R.drawable.menu_star))
                        .chunked(3).forEach { row ->
                            Row(Modifier.fillMaxWidth()) {
                                row.forEach { item ->
                                    Column(Modifier.weight(1f).clickable { open(item.title) }.padding(vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(Modifier.size(30.dp).background(if (item.title == "카카오톡") Color(0xFFFFF6CD) else Color(0xFFEEF3F8), RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                                            MenuIcon(item.icon, if (item.title == "카카오톡") Color(0xFF4B4230) else Accent)
                                        }
                                        Text(item.title, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp))
                                    }
                                }
                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                }
                Row(Modifier.fillMaxWidth().padding(top = 11.dp).background(Color(0xFFEEF1F5), RoundedCornerShape(10.dp)).clickable { open("종결 봇") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("종결 봇 · ${connection.label}", color = if (connection == BotConnection.CONNECTED) Color(0xFF438970) else if (connection == BotConnection.LOGIN_FAILED) Color(0xFFAE5A58) else Soft, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    MenuIcon(R.drawable.menu_chevron, size = 13)
                }
                SectionLabel("차량 업무")
                Panel {
                    MenuRow(MenuItem("차량 등록", R.drawable.menu_add), open)
                    HorizontalDivider(color = Line)
                    MenuRow(MenuItem("상담", R.drawable.menu_call), open)
                }
                SectionLabel(if (state.session?.isAdmin == true) "관리" else "직원")
                Panel {
                    val items = if (state.session?.isAdmin == true) listOf(MenuItem("직원 관리", R.drawable.menu_users), MenuItem("견적·계약서", R.drawable.menu_document),
                        MenuItem("변경기록", R.drawable.menu_history), MenuItem("회사 설정", R.drawable.menu_settings))
                    else listOf(MenuItem("직원 목록", R.drawable.menu_users), MenuItem("내 위치 공유", R.drawable.menu_location))
                    items.chunked(2).forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = Line)
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { item ->
                                Row(Modifier.weight(1f).clickable { open(item.title) }.padding(horizontal = 11.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MenuIcon(item.icon, size = 17)
                                    Text(item.title, fontSize = 11.sp, modifier = Modifier.padding(start = 9.dp))
                                }
                            }
                        }
                    }
                }
                SectionLabel("앱 설정")
                Panel { MenuRow(MenuItem("내 앱 설정", R.drawable.menu_settings), open) }
                Row {
                    TextButton(onClick = { open("새로고침") }, enabled = !state.busy) { Text("새로고침", color = Soft, fontSize = 10.sp) }
                    TextButton(onClick = { open("로그아웃") }) { Text("로그아웃", color = Soft, fontSize = 10.sp) }
                }
                Text("전용 앱 시험판 · 이전 중인 기능은 기존 앱을 이용해주세요.", color = Soft, fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
}
@Composable private fun SectionLabel(text: String) {
    Text(text, color = Soft, fontSize = 11.sp, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp))
}
@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).border(1.dp, Line, RoundedCornerShape(12.dp)), content = content)
}
@Composable private fun MenuRow(item: MenuItem, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { open(item.title) }.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
        MenuIcon(item.icon, size = 17)
        Text(item.title, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 11.dp))
        MenuIcon(R.drawable.menu_chevron, size = 13)
    }
}
