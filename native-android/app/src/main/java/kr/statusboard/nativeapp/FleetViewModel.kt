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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.*
import org.json.JSONObject

data class PendingFleetPhoto(val id: String, val caption: String, val photoIds: List<String>, val reading: PhotoReading, val candidates: List<String>)

data class FleetUiState(
    val signedIn: Boolean = false, val busy: Boolean = false, val message: String = "", val unassigned: Boolean = false,
    val session: FleetSession? = null, val companyName: String = "현황판",
    val vehicles: List<FleetVehicle> = emptyList(), val cached: Boolean = false,
    val schedules: JSONObject = JSONObject(), val scheduleLoaded: Boolean = false,
    val homeBranch: String = "기본 지점", val longBranch: String = "장기",
    val chat: JSONObject = JSONObject(), val members: JSONObject = JSONObject(), val wookyJobs: JSONObject = JSONObject(),
    val realtimeConnected: Boolean = false, val sending: Boolean = false, val pendingPhoto: PendingFleetPhoto? = null,
    val loadingOlder: Boolean = false, val noOlder: Boolean = false, val inviteCode: String = "",
    val locations: JSONObject = JSONObject(), val locationSettings: JSONObject = JSONObject(), val locationSettingsLoaded: Boolean = false,
    val locationRevision: Int = 0,
    val paymentSettings: JSONObject = JSONObject(), val paymentOverrides: JSONObject = JSONObject(),
    val generalSales: JSONObject = JSONObject(), val paymentSendLog: JSONObject = JSONObject(),
    val quickApps: JSONObject = JSONObject(), val companySettings: JSONObject? = null, val history: JSONObject? = null,
    val documents: JSONObject? = null, val inquiries: JSONObject = JSONObject(), val chatHistory: JSONObject? = null, val legacyQuickApp: JSONObject? = null
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
    private var older = JSONObject()
    private val _state = MutableStateFlow(FleetUiState())
    val state = _state.asStateFlow()
    private var generation = 0
    init {
        // An old widget snapshot is not exposed before company membership is verified.
        FleetWidgets.clear(application)
        viewModelScope.launch {
            state.collectLatest { value ->
                delay(500)
                FleetWidgets.publish(application, value) { _state.value.session?.cacheKey }
                if (_state.value.session?.cacheKey == value.session?.cacheKey && auth.currentUser?.uid == value.session?.uid)
                    FleetNotifications.schedule(application, value)
                value.session?.let { session ->
                    if (_state.value.session?.cacheKey == session.cacheKey) {
                        try { FleetPush.sync(application, session) }
                        catch (error: Exception) { if (error is CancellationException) throw error }
                    }
                }
            }
        }
        if (auth.currentUser != null) refresh()
    }
    fun markChatRead() {
        val session = _state.value.session ?: return
        FleetWidgets.markChatRead(getApplication(), session.cacheKey)
        viewModelScope.launch { FleetWidgets.publish(getApplication(), _state.value) { _state.value.session?.cacheKey } }
    }
    fun locationChanged() { _state.value = _state.value.copy(locationRevision = _state.value.locationRevision + 1) }

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
    fun resetPassword(email: String) {
        if (_state.value.busy || email.isBlank()) return
        _state.value = _state.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try { auth.sendPasswordResetEmail(email.trim()).await(); _state.value = _state.value.copy(busy = false, message = "비밀번호 재설정 메일을 요청했습니다. 받은 편지함을 확인해주세요.") }
            catch (error: Exception) { if (error is CancellationException) throw error; _state.value = _state.value.copy(busy = false, message = "재설정 메일을 요청하지 못했습니다. 이메일과 연결을 확인해주세요.") }
        }
    }
    fun enroll(email: String, password: String, company: String?, invite: String?) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = "")
        viewModelScope.launch {
            try { FleetEnrollment(auth, transport).enroll(email, password, company, invite); _state.value = _state.value.copy(busy = false); refresh() }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                _state.value = _state.value.copy(busy = false, unassigned = auth.currentUser != null,
                    message = if (error is IllegalArgumentException || error is IllegalStateException) error.message.orEmpty() else "가입을 완료하지 못했습니다. 이미 가입한 이메일이면 로그인한 뒤 업체 연결을 진행해주세요.")
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
                val membership = NativeMembership(transport)
                if (membership.companyFor(user.uid) == null) {
                    if (epoch == generation) _state.value = FleetUiState(unassigned = true, message = "연결된 업체가 없습니다. 초대코드 가입 또는 업체 만들기를 진행해주세요.")
                    return@launch
                }
                val session = FleetAccessResolver(membership).resolve(user.uid) ?: throw AccessDenied()
                verified = session
                if (epoch != generation) return@launch
                if (_state.value.session?.cacheKey != session.cacheKey) { older = JSONObject(); _state.value = FleetUiState(busy = true) }
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
                            "profile" -> {
                                val profile = value as? JSONObject
                                _state.value = _state.value.copy(
                                    companyName = profile?.optString("name")?.takeIf(String::isNotBlank) ?: "현황판",
                                    homeBranch = profile?.optString("homeBranch")?.takeIf(String::isNotBlank) ?: _state.value.homeBranch,
                                    longBranch = profile?.optString("longTermBranch")?.takeIf(String::isNotBlank) ?: "장기")
                            }
                            "schedules" -> _state.value = _state.value.copy(schedules = value as? JSONObject ?: JSONObject(), scheduleLoaded = true)
                            "chat" -> {
                                val recent = value as? JSONObject ?: JSONObject()
                                val merged = JSONObject(older.toString())
                                recent.keys().forEach { key -> merged.put(key, recent.opt(key)) }
                                _state.value = _state.value.copy(chat = merged)
                            }
                            "members" -> {
                                val members = value as? JSONObject ?: JSONObject()
                                val own = members.optJSONObject(session.uid)
                                if (own == null) viewModelScope.launch { revoke(epoch) }
                                else _state.value = _state.value.copy(members = members, session = session.copy(role = own.optString("role", "staff")),
                                    companySettings = if (own.optString("role") == "owner") _state.value.companySettings else null,
                                    documents = if (own.optString("role") == "owner") _state.value.documents else null,
                                    history = if (own.optString("role") == "owner") _state.value.history else null)
                            }
                            "wookyJobs" -> {
                                val jobs = value as? JSONObject ?: JSONObject()
                                _state.value = _state.value.copy(wookyJobs = jobs)
                                launch {
                                    try { operations.announceWooky(session, jobs) }
                                    catch (error: Exception) { if (error is CancellationException) throw error }
                                }
                            }
                            "locations" -> _state.value = _state.value.copy(locations = value as? JSONObject ?: JSONObject())
                            "locationSettings" -> _state.value = _state.value.copy(locationSettings = value as? JSONObject ?: JSONObject(), locationSettingsLoaded = true)
                            "paymentSettings" -> _state.value = _state.value.copy(paymentSettings = value as? JSONObject ?: JSONObject())
                            "paymentOverrides" -> _state.value = _state.value.copy(paymentOverrides = value as? JSONObject ?: JSONObject())
                            "generalSales" -> _state.value = _state.value.copy(generalSales = value as? JSONObject ?: JSONObject())
                            "paymentSendLog" -> _state.value = _state.value.copy(paymentSendLog = value as? JSONObject ?: JSONObject())
                            "quickApps" -> _state.value = _state.value.copy(quickApps = value as? JSONObject ?: JSONObject())
                            "quickApp" -> _state.value = _state.value.copy(legacyQuickApp = value as? JSONObject)
                            "inquiries" -> _state.value = _state.value.copy(inquiries = value as? JSONObject ?: JSONObject())
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
        streams.close(); pendingMessage = null; older = JSONObject()
        FleetWidgets.clear(getApplication())
        FleetLocation.stop(getApplication())
        FleetNotifications.clear(getApplication())
        FleetPrivateFiles.clear(getApplication())
        FleetPush.clear(getApplication())
        val old = _state.value.session
        auth.signOut()
        _state.value = FleetUiState(message = "업체 접근 권한을 확인하지 못했습니다. 인터넷 연결을 확인하고 다시 로그인해주세요.")
        if (old != null) cache.remove(old.cacheKey)
    }
    fun logout() {
        generation++
        streams.close(); pendingMessage = null; older = JSONObject()
        FleetWidgets.clear(getApplication())
        FleetLocation.stop(getApplication())
        FleetNotifications.clear(getApplication())
        FleetPrivateFiles.clear(getApplication())
        val previous = _state.value.session
        FleetPush.clear(getApplication())
        _state.value = FleetUiState(busy = true)
        viewModelScope.launch {
            try { withTimeoutOrNull(3000) { FleetPush.detach(getApplication(), previous) } }
            catch (error: Exception) { if (error is CancellationException) throw error }
            finally { auth.signOut(); _state.value = FleetUiState() }
            if (previous != null) cache.remove(previous.cacheKey)
        }
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
                launch {
                    try { FleetPush.notifyChat(active, pending.first, text, _state.value.members.optJSONObject(active.uid)?.optString("name").orEmpty().ifBlank { "현황판" }) }
                    catch (error: Exception) { if (error is CancellationException) throw error }
                }
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
                launch {
                    try { FleetPush.notifyChat(session, id, caption.ifBlank { "사진 ${ids.size}장" }, "현황판") }
                    catch (error: Exception) { if (error is CancellationException) throw error }
                }
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
    fun setInquiryContacted(key: String, contacted: Boolean) = edit({ operations.setInquiryContacted(it, key, contacted) })
    fun deleteVehicle(original: FleetVehicle, id: String, complete: (Boolean) -> Unit) = edit({ operations.deleteVehicle(it, original, id) }, complete)
    fun savePaymentSettings(settings: Map<String, Any?>, complete: (Boolean) -> Unit) = edit({ operations.savePaymentSettings(it, settings) }, complete)
    fun addVehicles(vehicles: List<Map<String, Any?>>, id: String, complete: (Boolean) -> Unit) = edit({ operations.addVehicles(it, vehicles, id) }, complete)
    fun readRegistration(uris: List<Uri>, complete: (List<Map<String, String>>?) -> Unit) = edit({ session ->
        val data = uris.take(12).map { FleetPhotos.compress(getApplication(), it) }
        val result = vision.registration(session, data)
        if (_state.value.session?.cacheKey == session.cacheKey) complete(result)
    }) { if (!it) complete(null) }
    fun savePaymentOverride(plate: String, fields: Map<String, Any?>) = edit({ operations.savePaymentOverride(it, plate, fields) })
    fun markPaymentSent(plate: String, month: String, message: String, sent: Boolean) = edit({ operations.markPaymentSent(it, plate, month, message, sent) })
    fun markBilled(key: String, billed: Boolean) = edit({ operations.markBilled(it, key, billed) })
    fun setSalePaid(key: String, paid: Boolean) = edit({ operations.setSalePaid(it, key, paid) })
    fun saveSale(key: String, id: String, fields: Map<String, Any?>, version: String?, complete: (Boolean) -> Unit) = edit({ operations.saveSale(it, key, id, fields, version) }, complete)
    fun deleteSale(key: String, id: String, version: String?, complete: (Boolean) -> Unit) = edit({ operations.deleteSale(it, key, id, version) }, complete)
    fun addManualReturn(id: String, plate: String, at: String, complete: (Boolean) -> Unit) = edit({ operations.addManualReturn(it, id, plate, at) }, complete)
    fun deletePaymentLog(key: String) = edit({ operations.deletePaymentLog(it, key) })
    fun loadSettings() = edit({ session ->
        val result = operations.settings(session)
        if (_state.value.session?.cacheKey == session.cacheKey) _state.value = _state.value.copy(companySettings = result)
    })
    fun clearSettings() { _state.value = _state.value.copy(companySettings = null) }
    fun loadHistory() = edit({ session ->
        val result = operations.history(session)
        if (_state.value.session?.cacheKey == session.cacheKey) _state.value = _state.value.copy(history = result)
    })
    fun restoreHistory(key: String, id: String, complete: (Boolean) -> Unit) = edit({ operations.restoreHistory(it, key, id) }, complete)
    fun loadDocuments() = edit({ session ->
        val result = operations.documents(session)
        if (_state.value.session?.cacheKey == session.cacheKey) _state.value = _state.value.copy(documents = result)
    })
    fun clearDocuments() { _state.value = _state.value.copy(documents = null) }
    fun newDocumentKey(): String? = _state.value.session?.let(operations::newId)
    fun saveDocument(key: String, request: String, data: Map<String, Any?>, version: String?, complete: (Boolean) -> Unit) = edit({ operations.saveDocument(it, key, request, data, version) }, complete)
    fun deleteDocument(key: String) = edit({ operations.deleteDocument(it, key) }) { if (it) loadDocuments() }
    fun saveDocumentRates(rates: Map<String, Any?>, complete: (Boolean) -> Unit) = edit({ operations.saveDocumentRates(it, rates) }, complete)
    fun saveSettings(profile: Map<String, Any?>, ai: Map<String, Any?>, start: String, end: String, complete: (Boolean) -> Unit) = edit({ operations.saveSettings(it, profile, ai, start, end) }, complete)
    fun saveQuickApp(key: String?, label: String, url: String, complete: (Boolean) -> Unit) = edit({ operations.saveQuickApp(it, key, label, url) }, complete)
    fun deleteQuickApp(key: String) = edit({ operations.deleteQuickApp(it, key) })
    fun setMemberName(uid: String, name: String, complete: (Boolean) -> Unit) = edit({ operations.setMemberName(it, uid, name) }, complete)
    fun setMemberRole(uid: String, role: String) = edit({ operations.setMemberRole(it, uid, role) })
    fun removeMember(uid: String) = edit({ operations.removeMember(it, uid) })
    fun createInvite() = edit({ session ->
        val key = session.cacheKey; val code = operations.createInvite(session)
        if (_state.value.session?.cacheKey == key) _state.value = _state.value.copy(inviteCode = code)
    })
    fun deleteChat(id: String) = edit({ session ->
        operations.deleteChat(session, id)
        if (_state.value.session?.cacheKey == session.cacheKey) {
            older.remove(id)
            val messages = JSONObject(_state.value.chat.toString()); messages.remove(id)
            _state.value = _state.value.copy(chat = messages)
            _state.value.chatHistory?.let { history -> val copy = JSONObject(history.toString()); copy.remove(id); _state.value = _state.value.copy(chatHistory = copy) }
        }
    })
    fun searchChat(query: String, complete: (JSONObject?) -> Unit) = edit({ session ->
        val result = operations.searchChat(session, query)
        if (_state.value.session?.cacheKey == session.cacheKey) complete(result)
    }) { if (!it) complete(null) }
    fun jumpChat(id: String, complete: (Boolean) -> Unit) = edit({ session ->
        val result = operations.chatContext(session, id)
        if (_state.value.session?.cacheKey == session.cacheKey) _state.value = _state.value.copy(chatHistory = result)
    }, complete)
    fun recentChat() { _state.value = _state.value.copy(chatHistory = null) }
    fun backupChat(complete: (java.io.File?) -> Unit) = edit({ session ->
        val file = operations.backupChat(session, getApplication())
        if (_state.value.session?.cacheKey == session.cacheKey) complete(file) else file.delete()
    }) { if (!it) complete(null) }
    fun loadOlderChat() {
        val session = _state.value.session ?: return
        if (_state.value.loadingOlder || _state.value.noOlder) return
        val epoch = generation
        val before = _state.value.chat.keys().asSequence().minWithOrNull(compareBy<String> { _state.value.chat.optJSONObject(it)?.optString("at").orEmpty() }.thenBy { it })
        _state.value = _state.value.copy(loadingOlder = true)
        viewModelScope.launch {
            try {
                val page = operations.olderChat(session, before)
                if (epoch != generation) return@launch
                page.keys().forEach { older.put(it, page.opt(it)) }
                val merged = JSONObject(page.toString())
                _state.value.chat.keys().forEach { merged.put(it, _state.value.chat.opt(it)) }
                _state.value = _state.value.copy(chat = merged, loadingOlder = false, noOlder = page.length() < 50)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch == generation) {
                    if (error is AccessDenied) revoke(epoch)
                    else _state.value = _state.value.copy(loadingOlder = false, message = "이전 대화를 불러오지 못했습니다.")
                }
            }
        }
    }
    fun saveVehicle(original: FleetVehicle, fields: Map<String, Any?>, id: String, extend: Boolean = false, complete: (Boolean) -> Unit) =
        edit({ operations.saveVehicle(it, original, fields, id, extend, _state.value.homeBranch, _state.value.longBranch) }, complete)
    override fun onCleared() { streams.close(); super.onCleared() }
}
