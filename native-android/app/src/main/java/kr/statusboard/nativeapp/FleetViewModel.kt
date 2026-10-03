package kr.statusboard.nativeapp

import android.app.Application
import android.net.Uri
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

data class PendingFleetPhoto(val id: String, val caption: String, val photoIds: List<String>, val reading: PhotoReading, val candidates: List<String>)

data class FleetUiState(
    val signedIn: Boolean = false, val busy: Boolean = false, val message: String = "",
    val session: FleetSession? = null, val companyName: String = "현황판",
    val vehicles: List<FleetVehicle> = emptyList(), val cached: Boolean = false,
    val schedules: JSONObject = JSONObject(), val scheduleLoaded: Boolean = false,
    val homeBranch: String = "기본 지점", val longBranch: String = "장기",
    val chat: JSONObject = JSONObject(), val members: JSONObject = JSONObject(), val wookyJobs: JSONObject = JSONObject(),
    val realtimeConnected: Boolean = false, val sending: Boolean = false, val pendingPhoto: PendingFleetPhoto? = null
)
class FleetViewModel(application: Application) : AndroidViewModel(application) {
    private val auth = FirebaseAuth.getInstance()
    private val transport = FleetTransport(auth)
    private val cache = EncryptedFleetCache(application)
    private val repository = FleetRepository(cache, NativeFleetGateway(transport))
    private val streams = FleetStreams()
    private val operations = FleetOperations(auth, transport)
    private val vision by lazy { FleetVision(application, transport) }
    private var pendingMessage: Pair<String, String>? = null
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
        streams.close()
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
                streams.bind(session, { name, value ->
                    if (epoch == generation) {
                        when (name) {
                            "vehicles" -> _state.value = _state.value.copy(vehicles = VehicleCodec.decode(value), cached = false)
                            "schedules" -> _state.value = _state.value.copy(schedules = value as? JSONObject ?: JSONObject(), scheduleLoaded = true)
                            "chat" -> _state.value = _state.value.copy(chat = value as? JSONObject ?: JSONObject())
                            "members" -> {
                                val members = value as? JSONObject ?: JSONObject()
                                val own = members.optJSONObject(session.uid)
                                if (own == null) viewModelScope.launch { revoke(epoch) }
                                else _state.value = _state.value.copy(members = members, session = session.copy(role = own.optString("role", "staff")))
                            }
                            "wookyJobs" -> _state.value = _state.value.copy(wookyJobs = value as? JSONObject ?: JSONObject())
                            "connected" -> _state.value = _state.value.copy(realtimeConnected = value == true)
                            "error" -> _state.value = _state.value.copy(message = value.toString())
                        }
                    }
                }, { viewModelScope.launch { revoke(epoch) } })
                launch {
                    try { operations.recover(session) }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (error is AccessDenied) revoke(epoch)
                        else if (epoch == generation) _state.value = _state.value.copy(message = "저장된 요청의 후속 처리가 남아 있습니다. 연결 복구 후 새로고침해주세요.")
                    }
                }
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
        streams.close(); pendingMessage = null
        val old = _state.value.session
        auth.signOut()
        _state.value = FleetUiState(message = "업체 접근 권한을 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 로그인해주세요.")
        if (old != null) cache.remove(old.cacheKey)
    }
    fun logout() {
        generation++
        streams.close(); pendingMessage = null
        val previous = _state.value.session
        auth.signOut(); _state.value = FleetUiState()
        if (previous != null) viewModelScope.launch { cache.remove(previous.cacheKey) }
    }
    fun sendText(text: String, complete: (Boolean) -> Unit) {
        val active = _state.value.session ?: return
        if (_state.value.sending || text.isBlank()) return
        val epoch = generation
        val pending = pendingMessage?.takeIf { it.second == text } ?: (operations.newId(active) to text)
        pendingMessage = pending
        _state.value = _state.value.copy(sending = true, message = "")
        viewModelScope.launch {
            try {
                operations.sendText(active, pending.first, text, _state.value.homeBranch, _state.value.longBranch)
                if (epoch == generation) { pendingMessage = null; _state.value = _state.value.copy(sending = false); complete(true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(sending = false, message = "저장 응답을 확인하지 못했습니다. 입력 내용으로 다시 시도하면 같은 요청을 확인합니다.")
                    complete(false)
                }
            }
        }
    }
    fun retryWooky() {
        val session = _state.value.session ?: return
        if (_state.value.sending || !session.isAdmin) return
        val epoch = generation
        _state.value = _state.value.copy(sending = true, message = "")
        viewModelScope.launch {
            try {
                val count = operations.retryWooky(session)
                if (epoch == generation) _state.value = _state.value.copy(sending = false, message = if (count > 0) "${count}건 재시도를 요청했습니다." else "재시도할 실패 건이 없거나 이미 대기 중입니다.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(sending = false, message = "재시도 요청 저장에 실패했습니다. 연결을 확인해주세요.")
                }
            }
        }
    }
    fun sendPhotos(caption: String, uris: List<Uri>, complete: (Boolean) -> Unit) {
        val session = _state.value.session ?: return
        if (_state.value.sending || uris.isEmpty()) return
        val epoch = generation; val id = operations.newId(session)
        _state.value = _state.value.copy(sending = true, message = "사진 준비 중…")
        viewModelScope.launch {
            try {
                val access = FleetAccessResolver(NativeMembership(transport)).resolve(session.uid)
                if (access?.cacheKey != session.cacheKey) throw AccessDenied()
                val data = uris.take(12).map { FleetPhotos.compress(getApplication(), it) }
                if (epoch != generation) return@launch
                val ids = FleetPhotos.upload(session, id, data, null)
                if (epoch != generation) return@launch
                operations.sendText(session, id, caption, _state.value.homeBranch, _state.value.longBranch, ids, applyCommands=false)
                if (epoch != generation) return@launch
                if (!FleetPhotoRouting.needsAnalysis(caption, _state.value.longBranch)) { _state.value = _state.value.copy(sending=false, message="사진 전송됨"); complete(true); return@launch }
                _state.value = _state.value.copy(message = "사진의 번호판·주행거리 확인 중…")
                val reading = vision.read(session, data)
                if (epoch != generation) return@launch
                val route = FleetPhotoRouting.route(caption, reading, _state.value.vehicles, _state.value.longBranch)
                if (route.chooseVehicle) {
                    _state.value = _state.value.copy(sending=false, message=reading.reason, pendingPhoto=PendingFleetPhoto(id,caption,ids,reading,route.candidates))
                } else {
                    route.command?.let { operations.sendText(session,id,caption,_state.value.homeBranch,_state.value.longBranch,ids,it.copy(photoId=ids.first())) }
                    if (epoch == generation) _state.value = _state.value.copy(sending=false,message="현황판에 반영됨")
                }
                if (epoch == generation) complete(true)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(sending=false,message="사진 처리 중 확인이 필요합니다. 전송된 대화와 차량 상태를 먼저 확인해주세요.")
                    complete(false)
                }
            }
        }
    }
    fun choosePhotoVehicle(plate: String?) {
        val pending = _state.value.pendingPhoto ?: return
        val session = _state.value.session ?: return
        if (plate == null) { _state.value = _state.value.copy(pendingPhoto=null,message="사진만 전송했습니다. 차량 명령은 실행하지 않았습니다."); return }
        val epoch = generation
        _state.value = _state.value.copy(sending=true,message="")
        viewModelScope.launch {
            try {
                val route = FleetPhotoRouting.route(pending.caption,pending.reading,_state.value.vehicles,_state.value.longBranch,plate)
                val command = route.command ?: error("차량을 선택해주세요.")
                operations.sendText(session,pending.id,pending.caption,_state.value.homeBranch,_state.value.longBranch,pending.photoIds,command.copy(photoId=pending.photoIds.first()))
                if (epoch == generation) _state.value = _state.value.copy(sending=false,pendingPhoto=null,message="현황판에 반영됨")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(sending=false,message="선택한 차량 처리를 확인하지 못했습니다. 차량 상태를 확인한 뒤 다시 시도해주세요.")
                }
            }
        }
    }
    private fun edit(action: suspend (FleetSession) -> Unit, complete: (Boolean) -> Unit = {}) {
        val session = _state.value.session ?: return
        if (_state.value.sending || _state.value.cached) return
        val epoch = generation
        _state.value = _state.value.copy(sending = true, message = "")
        viewModelScope.launch {
            try {
                action(session)
                if (epoch == generation) { _state.value = _state.value.copy(sending = false, message = "저장했습니다."); complete(true) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(sending = false, message = error.message ?: "저장 결과를 확인하지 못했습니다.")
                    complete(false)
                }
            }
        }
    }
    fun saveSchedule(key: String?, title: String, date: String, repeat: Boolean, memo: String, complete: (Boolean) -> Unit) =
        edit({ operations.saveSchedule(it, key, title, date, repeat, memo) }, complete)
    fun toggleSchedule(key: String, date: String) = edit({ operations.toggleSchedule(it, key, date) })
    fun deleteSchedule(key: String) = edit({ operations.deleteSchedule(it, key) })
    fun saveVehicle(original: FleetVehicle, fields: Map<String, Any?>, complete: (Boolean) -> Unit) =
        edit({ operations.saveVehicle(it, original, fields) }, complete)
    override fun onCleared() { streams.close(); super.onCleared() }
}
