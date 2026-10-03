package kr.statusboard.nativeapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import kr.statusboard.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

class AccessDenied : Exception("업체 접근 권한을 확인할 수 없습니다. 다시 로그인해주세요.")
class FleetTransport(private val auth: FirebaseAuth) {
    suspend fun read(path: String): Any? = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: throw AccessDenied()
        val token = user.getIdToken(false).await().token ?: throw AccessDenied()
        val base = FirebaseApp.getInstance().options.databaseUrl!!.trimEnd('/')
        val safePath = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }
        val connection = URL("$base/$safePath.json?auth=${URLEncoder.encode(token, "UTF-8")}")
            .openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            connection.requestMethod = "GET"; connection.useCaches = false
            val code = connection.responseCode
            if (code == 401 || code == 403) throw AccessDenied()
            if (code !in 200..299) throw Exception("자료 조회 실패 ($code). 잠시 후 다시 시도해주세요.")
            val json = JSONTokener(connection.inputStream.bufferedReader().use { it.readText() }).nextValue()
            if (json == JSONObject.NULL) null else json
        } finally { connection.disconnect() }
    }
}

class NativeMembership(private val transport: FleetTransport) : MembershipGateway {
    override suspend fun companyFor(uid: String): String? =
        (transport.read("userIndex/$uid") as? JSONObject)?.optString("companyId")?.takeIf { it.isNotBlank() }
    override suspend fun roleFor(companyId: String, uid: String): String? {
        // Validate paths before using a server-supplied company id.
        FleetSession(uid, companyId, "staff")
        val member = transport.read("companies/$companyId/members/$uid") as? JSONObject ?: return null
        return member.optString("role", "staff")
    }
}

object VehicleCodec {
    fun decode(value: Any?): List<FleetVehicle> {
        val indexed = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { i -> (value.opt(i) as? JSONObject)?.let { i to it } }
            is JSONObject -> value.keys().asSequence().mapNotNull { key ->
                key.toIntOrNull()?.let { index -> (value.opt(key) as? JSONObject)?.let { index to it } }
            }.sortedBy { it.first }.toList()
            null -> emptyList()
            else -> throw IllegalArgumentException("차량 데이터 형식을 확인해주세요.")
        }
        return indexed.map { (index, raw) ->
            fun field(name: String): String? = raw.opt(name)?.takeUnless { it == JSONObject.NULL }?.toString()
            FleetVehicle(index, field("plate").orEmpty(), field("model").orEmpty(), field("status").orEmpty(),
                field("type"), field("branch"), field("note"), field("returnDate"),
                raw.keys().asSequence().associateWith { raw.opt(it).takeUnless { v -> v == JSONObject.NULL } })
        }
    }
    fun encode(snapshot: FleetSnapshot): String = JSONObject().put("capturedAt", snapshot.capturedAt)
        .put("vehicles", JSONArray(snapshot.vehicles.map { JSONObject(it.rawFields).put("_sourceIndex", it.sourceIndex) })).toString()
    fun cached(text: String): FleetSnapshot {
        val root = JSONObject(text)
        val list = decode(root.getJSONArray("vehicles")).map { vehicle ->
            val raw = vehicle.rawFields.toMutableMap()
            val index = (raw.remove("_sourceIndex") as? Number)?.toInt() ?: vehicle.sourceIndex
            vehicle.copy(sourceIndex = index, rawFields = raw)
        }
        return FleetSnapshot(list, root.getLong("capturedAt"))
    }
}

class NativeFleetGateway(private val transport: FleetTransport) : FleetGateway {
    override suspend fun readVehicles(session: FleetSession): FleetSnapshot =
        FleetSnapshot(VehicleCodec.decode(transport.read(session.path("vehicles"))), System.currentTimeMillis())
    override fun isAccessDenied(error: Exception) = error is AccessDenied
}

class EncryptedFleetCache(context: Context) : FleetCache {
    private val directory = File(context.noBackupFilesDir, "fleet-native-cache").apply { mkdirs() }
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("fleet-native-cache-v1", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("fleet-native-cache-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun file(id: String) = File(directory, MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".bin")
    override suspend fun read(key: String): FleetSnapshot? = withContext(Dispatchers.IO) {
        val file = file(key)
        if (!file.exists()) return@withContext null
        try {
            val bytes = file.readBytes()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, this@EncryptedFleetCache.key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(key.toByteArray())
            VehicleCodec.cached(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        } catch (_: Exception) { file.delete(); null }
    }
    override suspend fun write(key: String, snapshot: FleetSnapshot) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, this@EncryptedFleetCache.key); cipher.updateAAD(key.toByteArray())
        val payload = cipher.iv + cipher.doFinal(VehicleCodec.encode(snapshot).toByteArray())
        val destination = file(key); val temp = File(directory, destination.name + ".tmp")
        temp.writeBytes(payload)
        check(temp.renameTo(destination)) { "캐시 저장 실패" }
    }
    override suspend fun remove(key: String) { withContext(Dispatchers.IO) { file(key).delete() } }
}
