package kr.statusboard.nativeapp

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

internal data class FleetStaffPoint(val uid: String, val name: String, val lat: Double, val lng: Double, val at: String)

/** Only the existing Kakao map component is web-rendered. Auth/GPS/database remain native.
 * No Firebase credentials or JavaScript-to-native interface is exposed to this page. */
@SuppressLint("SetJavaScriptEnabled")
@Composable internal fun FleetLocationMap(points: List<FleetStaffPoint>, selected: String?, modifier: Modifier, addresses: (Map<String, String>) -> Unit, selectionRevision: Int = 0, error: (String) -> Unit) {
    val context = LocalContext.current
    val report = rememberUpdatedState(error)
    val showAddresses = rememberUpdatedState(addresses)
    var ready by remember { mutableStateOf(false) }
    val view = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun onPageFinished(view: WebView, url: String) {
                    if (url == "https://status-board-kr.github.io/native-location-map.html") ready = true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                    if (request.isForMainFrame) report.value("지도를 불러오지 못했습니다. 인터넷 연결을 확인해주세요.")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    if (message.message().startsWith("FLEET_MAP_ERROR")) report.value("카카오 지도를 불러오지 못했습니다. 인터넷 연결을 확인해주세요. 아래 직원 목록은 사용할 수 있습니다.")
                    return true
                }
            }
            loadUrl("https://status-board-kr.github.io/native-location-map.html")
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
    val payload = JSONArray(points.map { point -> JSONObject().put("uid", point.uid).put("name", point.name)
        .put("lat", point.lat).put("lng", point.lng).put("at", point.at) }).toString()
    LaunchedEffect(ready, payload, selected, selectionRevision) {
        if (ready) {
            view.evaluateJavascript("window.fleetMapUpdate && window.fleetMapUpdate($payload, ${JSONObject.quote(selected.orEmpty())});", null)
            repeat(10) {
                kotlinx.coroutines.delay(1000)
                view.evaluateJavascript("JSON.stringify(window.fleetMapAddresses ? window.fleetMapAddresses() : {});", { result ->
                    runCatching {
                        val text = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching
                        val data = JSONObject(text)
                        showAddresses.value(data.keys().asSequence().associateWith { data.optString(it) })
                    }
                })
            }
        }
    }
    DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
}
