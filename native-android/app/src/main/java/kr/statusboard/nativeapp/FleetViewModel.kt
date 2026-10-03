package kr.statusboard.nativeapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.*
import org.json.JSONObject

data class FleetUiState(
    val signedIn: Boolean = false, val busy: Boolean = false, val message: String = "",
    val session: FleetSession? = null, val companyName: String = "현황판",
    val vehicles: List<FleetVehicle> = emptyList(), val cached: Boolean = false,
    val schedules: JSONObject = JSONObject(), val scheduleLoaded: Boolean = false,
    val homeBranch: String = "기본 지점", val longBranch: String = "장기"
)
class FleetViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val transport = FleetTransport(auth)
    private val cache = EncryptedFleetCache(application)
    private val repository = FleetRepository(cache, NativeFleetGateway(transport))
    private val _state = MutableStateFlow(FleetUiState())
    val state = _state.asStateFlow()
    private var generation = 0
    init { if (auth.currentUser != null) refresh() }

    fun login(email: String, password: String) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try {
                auth.signInWithEmailAndPassword(email.trim(), password).await()
                _state.value = _state.value.copy(busy = false)
                refresh()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = FleetUiState(message = "로그인 실패. 이메일·비밀번호와 인터넷 연결을 확인해주세요.")
            }
        }
    }
    fun refresh() {
        if (_state.value.busy) return
        val user = auth.currentUser ?: return
        val epoch = ++generation
        _state.value = _state.value.copy(busy = true, message = "", scheduleLoaded = false)
        viewModelScope.launch {
            var verified: FleetSession? = null
            try {
                // Resolve membership from the server on every refresh, before exposing cache.
                val session = FleetAccessResolver(NativeMembership(transport)).resolve(user.uid) ?: throw AccessDenied()
                verified = session
                if (epoch != generation) return@launch
                if (_state.value.session?.cacheKey != session.cacheKey) _state.value = FleetUiState(busy = true)
                _state.value = _state.value.copy(signedIn = true, session = session)
                repository.load(session) { load ->
                    if (epoch == generation) _state.value = _state.value.copy(vehicles = load.snapshot.vehicles, cached = load.source == SnapshotSource.CACHE)
                }
                if (epoch != generation) return@launch
                _state.value = _state.value.copy(busy = false)
                val active = session
                launch {
                    try {
                        val profile = transport.read(active.path("profile")) as? JSONObject
                        if (epoch == generation) _state.value = _state.value.copy(
                            companyName = profile?.optString("name")?.takeIf { it.isNotBlank() } ?: "현황판",
                            homeBranch = profile?.optString("homeBranch")?.takeIf { it.isNotBlank() }
                                ?: _state.value.vehicles.firstOrNull { !it.branch.isNullOrBlank() && it.branch != "장기" }?.branch ?: "기본 지점",
                            longBranch = profile?.optString("longTermBranch")?.takeIf { it.isNotBlank() } ?: "장기")
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error is AccessDenied) revoke(epoch)
                    }
                }
                launch {
                    try {
                        val schedules = transport.read(active.path("schedules")) as? JSONObject ?: JSONObject()
                        if (epoch == generation) _state.value = _state.value.copy(schedules = schedules, scheduleLoaded = true)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error is AccessDenied) { revoke(epoch); return@launch }
                        if (epoch == generation) _state.value = _state.value.copy(message = "일정 자료를 불러오지 못했습니다. 새로고침해주세요.")
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch != generation) return@launch
                if (error is AccessDenied || verified == null) {
                    revoke(epoch)
                } else _state.value = _state.value.copy(busy = false, cached = true, message = "최신 자료를 받지 못했습니다. 표시된 저장 자료를 확인해주세요.")
            }
        }
    }
    private suspend fun revoke(epoch: Int) {
        if (epoch != generation) return
        generation++
        val old = _state.value.session
        auth.signOut()
        _state.value = FleetUiState(message = "업체 접근 권한을 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 로그인해주세요.")
        if (old != null) cache.remove(old.cacheKey)
    }
    fun logout() {
        generation++
        val previous = _state.value.session
        auth.signOut(); _state.value = FleetUiState()
        if (previous != null) viewModelScope.launch { cache.remove(previous.cacheKey) }
    }
}
