package kr.statusboard.nativeapp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.statusboard.core.FleetSession
import kr.statusboard.core.PhotoReading
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Timer
import java.util.TimerTask
import javax.net.ssl.HttpsURLConnection

/** Uses the same configured providers/models as the working app; keys are never bundled or logged.
 * JPEG re-encoding removes EXIF metadata before analysis. Provider errors stay generic.
 */
class FleetVision(context: Context, private val transport: FleetTransport) {
    private val config = JSONObject(context.assets.open("vision-client.json").bufferedReader().use { it.readText() })
    suspend fun read(session: FleetSession, photos: List<String>): PhotoReading = withContext(Dispatchers.IO) {
        val settings = transport.read(session.path("aiSettings")) as? JSONObject ?: return@withContext PhotoReading(reason = "AI 키가 없습니다. 차량을 직접 선택해주세요.")
        val gemini = settings.optString("geminiKey").ifBlank { if (settings.optString("provider") == "gemini") settings.optString("key") else "" }
        val grok = settings.optString("grokKey")
        if (gemini.isBlank() && grok.isBlank()) return@withContext PhotoReading(reason = "AI 키가 없습니다. 차량을 직접 선택해주세요.")
        val deadline = System.nanoTime() + 45_000_000_000L
        val allPlates = linkedSetOf<String>(); var km: Long? = null; var certain = true; var successful = false
        for (batch in photos.chunked(4)) {
            val images = batch.map { it.substringAfter(',') }
            var response: String? = null
            for (provider in listOf("gemini", "grok")) {
                val key = if (provider == "gemini") gemini else grok
                if (key.isBlank()) continue
                val models = config.getJSONArray(if (provider == "gemini") "geminiModels" else "grokModels")
                for (index in 0 until models.length()) {
                    val remaining = (deadline - System.nanoTime()) / 1_000_000L
                    if (remaining <= 0) break
                    val model = models.getString(index)
                    val payload = if (provider == "gemini") {
                        val parts = JSONArray(images.map { JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", it)) }).put(JSONObject().put("text", config.getString("prompt")))
                        JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", parts))).put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0))
                    } else {
                        val content = JSONArray(images.map { JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$it")) }).put(JSONObject().put("type", "text").put("text", config.getString("prompt")))
                        JSONObject().put("model", model).put("response_format", JSONObject().put("type", "json_object")).put("temperature", 0).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
                    }
                    try {
                        val (code, body) = post(if (provider == "gemini") "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent" else "https://api.x.ai/v1/chat/completions",
                            if (provider == "gemini") mapOf("x-goog-api-key" to key) else mapOf("Authorization" to "Bearer $key"), payload, minOf(25000, remaining).toInt())
                        if (code in 200..299) {
                            response = if (provider == "gemini") body.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.let { parts ->
                                (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }
                            } else body.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                            if (!response.isNullOrBlank()) break
                        }
                        if (code == 401 || code == 403 || (code == 400 && body.optJSONObject("error")?.optString("message")?.contains("API key", ignoreCase=true) == true)) break
                    } catch (error: Exception) { if (error is CancellationException) throw error }
                }
                if (!response.isNullOrBlank()) break
            }
            val parsed = try { response?.let { JSONObject(it.substring(it.indexOf('{'), it.lastIndexOf('}') + 1)) } } catch (_: Exception) { null }
            if (parsed == null) { certain = false; continue }
            successful = true
            certain = certain && parsed.optString("confidence") == "high"
            val plates = parsed.optJSONArray("plates") ?: JSONArray().also { if (parsed.optString("plate").isNotBlank()) it.put(parsed.optString("plate")) }
            for (index in 0 until plates.length()) {
                val plate = plates.optString(index).replace(Regex("\\s+"), "")
                if (plate.matches(Regex("[가-힣]{0,4}\\d{2,3}[가-힣]\\d{4}"))) allPlates += plate
            }
            if (km == null) parsed.opt("odometer_km")?.toString()?.replace(",", "")?.toLongOrNull()?.takeIf { it > 0 }?.let { km = it }
        }
        PhotoReading(allPlates.toList(), km, certain && successful, if (allPlates.isEmpty() && km == null) "사진을 확실하게 읽지 못했습니다. 차량을 직접 선택해주세요." else "")
    }
    private fun post(url: String, headers: Map<String, String>, payload: JSONObject, timeout: Int): Pair<Int, JSONObject> {
        val connection = URL(url).openConnection() as HttpsURLConnection
        val timer = Timer("fleet-vision-deadline", true)
        try {
            timer.schedule(object : TimerTask() { override fun run() { connection.disconnect() } }, timeout.toLong())
            connection.connectTimeout = timeout; connection.readTimeout = timeout
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            return code to json
        } finally { timer.cancel(); connection.disconnect() }
    }
}
