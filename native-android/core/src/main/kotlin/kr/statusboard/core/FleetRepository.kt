package kr.statusboard.core

import java.util.concurrent.CancellationException

/** Initial native data contract. Raw fields must be retained by the Firebase adapter. */
data class FleetVehicle(
    val sourceIndex: Int,
    val plate: String,
    val model: String,
    val status: String,
    val type: String?,
    val branch: String?,
    val note: String?,
    val returnDate: String?,
    val rawFields: Map<String, Any?>
)

data class FleetSnapshot(val vehicles: List<FleetVehicle>, val capturedAt: Long)
enum class SnapshotSource { CACHE, SERVER }
data class FleetLoad(val snapshot: FleetSnapshot, val source: SnapshotSource, val editable: Boolean)

interface FleetCache {
    suspend fun read(key: String): FleetSnapshot?
    suspend fun write(key: String, snapshot: FleetSnapshot)
    suspend fun remove(key: String)
}
interface FleetGateway {
    suspend fun readVehicles(session: FleetSession): FleetSnapshot
    fun isAccessDenied(error: Exception): Boolean
}

/** The caller must clear displayed cache on rejection; cached snapshots are never editable. */
class FleetRepository(private val cache: FleetCache, private val gateway: FleetGateway) {
    suspend fun load(session: FleetSession, publish: (FleetLoad) -> Unit) {
        cache.read(session.cacheKey)?.let { publish(FleetLoad(it, SnapshotSource.CACHE, false)) }
        val latest = try { gateway.readVehicles(session) } catch (error: Exception) {
            if (gateway.isAccessDenied(error)) cache.remove(session.cacheKey)
            throw error
        }
        // The source list defines order. Never sort by plate as a side effect of loading.
        try { cache.write(session.cacheKey, latest) } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Storage trouble must not hide successfully fetched server data.
        }
        publish(FleetLoad(latest, SnapshotSource.SERVER, true))
    }
}
