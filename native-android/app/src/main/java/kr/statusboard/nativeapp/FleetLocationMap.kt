package kr.statusboard.nativeapp

import android.annotation.SuppressLint
import android.webkit.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    var rendered by remember { mutableStateOf(false) }
    val view = remember {
        WebView(context).apply {
            // The map uses ordinary image tiles. Software rendering keeps them inside
            // the Compose dialog instead of losing the WebView's GPU surface on clipping.
            setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
            layoutParams = android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(android.graphics.Color.rgb(28, 42, 66))
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
    AndroidView(factory = { android.widget.FrameLayout(context).apply {
        clipChildren = true; clipToPadding = true; addView(view)
    } }, modifier = modifier.onSizeChanged {
        view.post { view.evaluateJavascript("window.fleetMapRelayout && window.fleetMapRelayout();", null) }
    }.semantics { contentDescription = if (rendered) "직원 위치 지도" else "직원 위치 지도 불러오는 중" })
    val payload = JSONArray(points.map { point -> JSONObject().put("uid", point.uid).put("name", point.name)
        .put("lat", point.lat).put("lng", point.lng).put("at", point.at) }).toString()
    LaunchedEffect(ready, payload, selected, selectionRevision) {
        if (ready) {
            view.evaluateJavascript("window.fleetMapUpdate && window.fleetMapUpdate($payload, ${JSONObject.quote(selected.orEmpty())});", null)
            // A slow SDK/geocoder must still update the list after the first ten seconds.
            // This reads local map state only; it does not poll Firebase or request another geocode.
            while (true) {
                kotlinx.coroutines.delay(2000)
                view.evaluateJavascript("window.fleetMapRendered ? window.fleetMapRendered() : false;", { result ->
                    if (result == "true") view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) { rendered = true }
                    })
                })
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
