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

private val Background get() = FleetAppearance.background
private val TextColor get() = FleetAppearance.text
private val Muted get() = FleetAppearance.sub
class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); FleetPush.foreground = true }
    override fun onPause() { FleetPush.foreground = false; super.onPause() }
    private val widgetOpen = mutableStateOf<String?>(null)
    private fun captureOpen(intent: Intent?) {
        intent?.getStringExtra("fleetOpen")?.let { widgetOpen.value = it; intent.removeExtra("fleetOpen") }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); captureOpen(intent)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetAppearance.load(this)
        captureOpen(intent)
        setContent {
            MaterialTheme(colorScheme = FleetAppearance.scheme()) {
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
@Composable internal fun Login(state: FleetUiState, model: FleetViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var invite by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Background).padding(WindowInsets.systemBars.asPaddingValues()).padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
    Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().background(WebPanel, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).border(1.dp, WebLine, androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(if (state.unassigned && mode == 0) "👋" else when (mode) { 0 -> "🚗"; 1 -> "🎟️"; else -> "🏢" }, fontSize = 40.sp)
        Text(if (state.unassigned && mode == 0) "거의 다 됐어요" else when (mode) { 0 -> "차량 현황판"; 1 -> "초대코드로 참여"; else -> "새 업체 등록" }, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (state.unassigned && mode == 0) "아직 소속된 업체가 없습니다.\n새 업체를 만들거나, 초대코드로 참여해주세요." else when (mode) { 0 -> "회사 계정으로 로그인해주세요."; 1 -> "업체 관리자에게 받은 6자리 코드를 입력하세요.\n대소문자는 신경 쓰지 않아도 돼요."; else -> "업체를 등록하면 관리자가 됩니다.\n직원은 초대코드로 추가할 수 있어요." }, color = Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
        if (!(state.unassigned && mode == 0)) {
        if (mode == 1) { WebInput(invite, { invite = it.uppercase().take(6) }, !state.busy, "초대코드 (예: K7M2QX)"); Spacer(Modifier.height(10.dp)) }
        if (mode == 2) { WebInput(company, { company = it }, !state.busy, "업체명 (예: OO렌트카)"); Spacer(Modifier.height(10.dp)) }
        WebInput(email, { email = it }, !state.busy && !state.unassigned, "이메일")
        Spacer(Modifier.height(10.dp))
        if (!state.unassigned) WebInput(password, { password = it }, !state.busy, "비밀번호", PasswordVisualTransformation())
        Button(onClick = {
            when (mode) { 0 -> model.login(email, password); 1 -> model.enroll(email, password, null, invite); else -> model.enroll(email, password, company, null) }
            password = ""
        }, enabled = !state.busy && (state.unassigned || (email.isNotBlank() && password.isNotEmpty())) && (mode != 1 || invite.length == 6) && (mode != 2 || company.isNotBlank()) && (!state.unassigned || mode != 0), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(if (state.busy) "확인 중…" else when (mode) { 0 -> "로그인"; 1 -> "초대코드로 가입"; else -> "업체 만들기" }) }
        }
        if (mode == 0) {
            if (!state.unassigned) {
                TextButton(onClick = { model.resetPassword(email) }, enabled = !state.busy && email.isNotBlank()) { Text("비밀번호 재설정", fontSize = 12.sp) }
                Text("— 처음 오셨나요? —", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
            OutlinedButton(onClick = { mode = 2 }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.unassigned) "새 업체 등록하기" else "새 업체로 시작하기") }
            TextButton(onClick = { mode = 1 }, enabled = !state.busy) { Text(if (state.unassigned) "초대코드로 참여하기" else "초대코드를 받았어요") }
            if (state.unassigned) TextButton(onClick = model::logout) { Text("다른 계정으로 로그인", fontSize = 12.sp) }
        } else TextButton(onClick = { mode = 0 }) { Text("← 로그인으로 돌아가기") }
        if (state.message.isNotBlank()) Text(state.message, color = Color(0xFFEAC483), modifier = Modifier.padding(top = 16.dp))
    }
    }
}
