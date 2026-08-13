package ch.trailer.android
import android.content.Context
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.geometry.LatLngBounds

class OfflineMapManager(private val appContext: Context) {
    private val offlineManager =
        OfflineManager.getInstance(appContext)

    fun downloadRegion(
        bounds: LatLngBounds,
        minZoom: Double = 10.0,
        maxZoom: Double = 15.0,
        onComplete: (OfflineRegion) -> Unit,
        onError: (String) -> Unit
    ) {
        val definition = OfflineTilePyramidRegionDefinition(
            "asset://swisstopo.json",
            bounds,
            minZoom,
            maxZoom,
            appContext.resources.displayMetrics.density
        )

        offlineManager.createOfflineRegion(
            definition,
            ByteArray(0),
            object : OfflineManager.CreateOfflineRegionCallback {

                override fun onCreate(offlineRegion: OfflineRegion) {
                    onComplete(offlineRegion)

                    offlineRegion.setDownloadState(
                        OfflineRegion.STATE_ACTIVE
                    )
                }

                override fun onError(error: String) {
                    onError(error)
                }
            }
        )
    }
}