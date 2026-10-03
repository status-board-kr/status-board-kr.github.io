package kr.statusboard.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kr.statusboard.core.FleetSession
import java.io.ByteArrayOutputStream
import java.time.Instant

object FleetPhotos {
    private const val MAX_DATA_URL = 550000
    suspend fun compress(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "읽을 수 없는 사진입니다." }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        var image = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("사진을 읽지 못했습니다.")
        val orientation = try { resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } } catch (_: Exception) { null }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f,1f); postRotate(270f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f,1f); postRotate(90f) }
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
            if (rotated !== image) image.recycle()
            image = rotated
        }
        try {
            val factor = minOf(1f, 1200f / maxOf(image.width, image.height))
            if (factor < 1f) {
                val resized = Bitmap.createScaledBitmap(image, maxOf(1,(image.width * factor).toInt()), maxOf(1,(image.height * factor).toInt()), true)
                if (resized !== image) image.recycle()
                image = resized
            }
            for (quality in listOf(78, 65, 50, 35)) {
                val bytes = ByteArrayOutputStream().use { stream -> image.compress(Bitmap.CompressFormat.JPEG, quality, stream); stream.toByteArray() }
                val data = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                if (data.length <= MAX_DATA_URL) return@withContext data
            }
            error("사진이 너무 큽니다. 더 작은 사진을 선택해주세요.")
        } finally { image.recycle() }
    }
    suspend fun upload(session: FleetSession, operationId: String, data: List<String>, plate: String?): List<String> = coroutineScope {
        require(data.isNotEmpty() && data.size <= 12)
        require(data.all { it.startsWith("data:image/jpeg;base64,") && it.length <= MAX_DATA_URL })
        val gate = Semaphore(2)
        data.mapIndexed { index, photo -> async {
            gate.withPermit {
                val id = "$operationId-photo-$index"
                FirebaseDatabase.getInstance().getReference(session.path("photos/$id")).setValue(mapOf(
                    "data" to photo, "uid" to session.uid, "plate" to plate, "at" to Instant.now().toString())).await()
                id
            }
        } }.awaitAll()
    }
}
