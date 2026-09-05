package ch.trailer.android

import android.content.Context
import ch.trailer.android.api.GeoJsonFeature
import ch.trailer.android.api.NetworkModule
import ch.trailer.android.database.TrailEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OfflineMapManager(private val appContext: Context) {
    private val offlineManager = OfflineManager.getInstance(appContext)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun downloadTrailTiles(
        trail: TrailEntity,
        minZoom: Double = 10.0,
        maxZoom: Double = 15.0
    ): OfflineRegion {
        val bounds = try {
            boundsFromGeoJson(trail.geoJSON).withPadding()
        } catch (e: Exception) {
            throw RuntimeException("Failed to parse trail GeoJSON: ${e.message}", e)
        }

        return suspendCancellableCoroutine { continuation ->
            downloadRegion(
                bounds = bounds,
                minZoom = minZoom,
                maxZoom = maxZoom,
                metadata = trail.id.toByteArray(Charsets.UTF_8),
                onComplete = { continuation.resume(it) },
                onError = { continuation.resumeWithException(RuntimeException(it)) }
            )
        }
    }

    suspend fun deleteTrailTiles(trail: TrailEntity) {
        suspendCancellableCoroutine { continuation ->
            offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val region = offlineRegions?.find { region ->
                        region.metadata.toString(Charsets.UTF_8) == trail.id
                    }

                    if (region != null) {
                        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() {
                                continuation.resume(Unit)
                            }

                            override fun onError(error: String) {
                                continuation.resumeWithException(RuntimeException(error))
                            }
                        })
                    } else {
                        continuation.resume(Unit)
                    }
                }

                override fun onError(error: String) {
                    continuation.resumeWithException(RuntimeException(error))
                }
            })
        }
    }

    suspend fun hasDownloadedTiles(trail: TrailEntity): Boolean {
        return suspendCancellableCoroutine { continuation ->
            offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val exists = offlineRegions?.any { region ->
                        region.metadata.toString(Charsets.UTF_8) == trail.id
                    } ?: false
                    continuation.resume(exists)
                }

                override fun onError(error: String) {
                    continuation.resumeWithException(RuntimeException(error))
                }
            })
        }
    }

    private fun downloadRegion(
        bounds: LatLngBounds,
        minZoom: Double,
        maxZoom: Double,
        metadata: ByteArray,
        onComplete: (OfflineRegion) -> Unit,
        onError: (String) -> Unit
    ) {
        val definition = OfflineTilePyramidRegionDefinition(
            NetworkModule.STYLE_URL,
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density
        )

        offlineManager.createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                    onComplete(offlineRegion)
                }

                override fun onError(error: String) {
                    onError(error)
                }
            }
        )
    }

    private fun boundsFromGeoJson(geoJson: String): LatLngBounds {
        val feature = json.decodeFromString<GeoJsonFeature>(geoJson)
        val builder = LatLngBounds.Builder()

        for (line in feature.geometry.coordinates) {
            for (coordinate in line) {
                require(coordinate.size >= 2) { "Invalid GeoJSON coordinate" }
                builder.include(LatLng(coordinate[1], coordinate[0]))
            }
        }

        return builder.build()
    }

    private fun LatLngBounds.withPadding(
        paddingFraction: Double = 0.1,
        minPadding: Double = 0.005
    ): LatLngBounds {
        val latPadding = maxOf((latitudeNorth - latitudeSouth) * paddingFraction, minPadding)
        val lonPadding = maxOf((longitudeEast - longitudeWest) * paddingFraction, minPadding)

        return LatLngBounds.Builder()
            .include(LatLng(latitudeNorth + latPadding, longitudeEast + lonPadding))
            .include(LatLng(latitudeSouth - latPadding, longitudeWest - lonPadding))
            .build()
    }
}
