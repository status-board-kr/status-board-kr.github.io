package kr.statusboard.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.statusboard.core.*
import org.json.JSONObject
import java.time.*

/** Debug-only, synthetic fixtures. No login, company reads, writes, photos or AI calls. */
class NativeVerificationActivity : ComponentActivity() {
    var screen by mutableStateOf("board")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = FleetAppearance.scheme()) {
                val model: FleetViewModel = viewModel()
                val date = LocalDate.now(ZoneId.of("Asia/Seoul"))
                val at = Instant.now().toString()
                val raw = mapOf<String, Any?>("plate" to "예시1234", "model" to "검증 차량", "branch" to "예시 지점", "type" to "일반", "status" to "운행중", "cls" to "중형", "fuel" to "LPG", "startDate" to date.minusDays(2).toString(), "returnDate" to date.toString(), "note" to "가상 자료 · 실제 차량 아님", "amount" to 10, "depositPaid" to true)
                val vehicle = FleetVehicle(0, "예시1234", "검증 차량", "운행중", "일반", "예시 지점", "가상 자료 · 실제 차량 아님", date.toString(), raw)
                val fixture = FleetUiState(signedIn = true, session = FleetSession("native-verification", "synthetic-example", "owner"), companyName = "가상 자료", homeBranch = "예시 지점", vehicles = listOf(vehicle), scheduleLoaded = true,
                    schedules = JSONObject().put("example", JSONObject().put("date", date.toString()).put("title", "가상 일정").put("memo", "실제 일정 아님").put("done", false)),
                    members = JSONObject().put("native-verification", JSONObject().put("role", "owner").put("name", "검증 관리자").put("email", "example@example.invalid")),
                    locations = JSONObject().put("native-verification", JSONObject().put("lat", 35.3016).put("lng", 126.7867).put("at", at)),
                    locationSettings = JSONObject().put("start", "09:00").put("end", "18:00"), locationSettingsLoaded = true,
                    chat = JSONObject().put("example", JSONObject().put("uid", "native-verification").put("email", "example@example.invalid").put("text", "가상 대화 · 실제 전송 아님").put("at", at)), noOlder = true,
                    companySettings = JSONObject().put("profile", JSONObject().put("name", "가상 자료").put("homeBranch", "예시 지점").put("longTermBranch", "장기")), documents = JSONObject())
                Surface(Modifier.fillMaxSize()) {
                    when (screen) {
                        "login" -> Login(FleetUiState(), model)
                        "chat" -> FleetChatDialog(fixture, model) {}
                        "schedule" -> FleetScheduleDialog(fixture, date, model) {}
                        "vehicle" -> FleetVehicleEditor(fixture, vehicle, model) {}
                        "location" -> FleetLocationDialog(fixture, model) {}
                        "staff" -> FleetStaffDialog(fixture, model) {}
                        "payments" -> FleetPaymentDialog(fixture, model) {}
                        "documents" -> FleetDocuments(fixture, model) {}
                        "settings" -> FleetAppSettings(fixture, model) {}
                        "registration" -> FleetRegistration(fixture, model) {}
                        "inquiries" -> FleetInquiries(fixture, model) {}
                        else -> FleetBoard(fixture, model) {}
                    }
                }
            }
        }
    }
}
