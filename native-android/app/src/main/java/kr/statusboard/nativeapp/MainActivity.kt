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
@Composable private fun Login(state: FleetUiState, model: FleetViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var invite by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("현황판", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("전용 앱 시험판 · 기존 현황판 계정으로 로그인", color = Muted, modifier = Modifier.padding(vertical = 12.dp))
        Row { listOf("로그인", "초대코드 가입", "업체 만들기").forEachIndexed { index, title -> TextButton(onClick = { mode = index }, enabled = !state.busy && (!state.unassigned || index != 0), modifier = Modifier.weight(1f)) { Text(title, fontSize = 11.sp) } } }
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
