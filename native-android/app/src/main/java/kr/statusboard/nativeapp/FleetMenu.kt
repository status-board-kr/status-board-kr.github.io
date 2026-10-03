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

private val Ink = Color(0xFF1C2D44)
private val Soft = Color(0xFF8190A5)
private val Line = Color(0xFFE2E8F0)
private val Accent = Color(0xFF375C84)
private val MenuBackground = Color(0xFFF6F8FB)
private data class MenuItem(val title: String, val icon: Int)

@Composable internal fun FleetShell(state: FleetUiState, refresh: () -> Unit, logout: () -> Unit) {
    var menu by rememberSaveable(state.session?.cacheKey) { mutableStateOf(false) }
    var schedule by rememberSaveable(state.session?.cacheKey) { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    val screens = rememberSaveableStateHolder()
    val open: (String) -> Unit = { title ->
        when (title) {
            "차량 현황" -> menu = false
            "전체 메뉴" -> menu = true
            "일정" -> schedule = true
            "새로고침" -> refresh()
            "로그아웃" -> logout()
            else -> pending = title
        }
    }
    BackHandler(enabled = menu && !schedule && pending == null) { menu = false }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (menu) FleetMenu(state, open)
            else screens.SaveableStateProvider("fleet") { FleetBoard(state, refresh, logout) }
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
    if (schedule) ScheduleDialog(state, LocalDate.now()) { schedule = false }
    pending?.let { title ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text(title) },
            text = { Text("이 기능은 전용 앱으로 옮기는 중입니다. 현재 업무는 기존 현황판 앱에서 이용해주세요.") },
            confirmButton = { TextButton(onClick = { pending = null }) { Text("확인") } })
    }
}

@Composable private fun MenuIcon(resource: Int, color: Color = Soft, size: Int = 19) {
    Icon(painterResource(resource), contentDescription = null, tint = color, modifier = Modifier.size(size.dp))
}

@Composable private fun FleetMenu(state: FleetUiState, open: (String) -> Unit) {
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
                    listOf(MenuItem("메신저", R.drawable.menu_chat), MenuItem("일정", R.drawable.menu_calendar), MenuItem("결제·미청구", R.drawable.menu_payment),
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
                            }
                        }
                }
                Row(Modifier.fillMaxWidth().padding(top = 11.dp).background(Color(0xFFEEF1F5), RoundedCornerShape(10.dp)).clickable { open("종결 봇") }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("종결 봇 · 상태 확인 준비 중", color = Soft, fontSize = 11.sp, modifier = Modifier.weight(1f))
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
                Text("전용 앱 시험판 · 차량·일정 조회 연결 / 나머지 기능 준비 중", color = Soft, fontSize = 10.sp,
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
