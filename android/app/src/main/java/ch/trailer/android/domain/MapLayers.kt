package ch.trailer.android.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DownhillSkiing
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.ui.graphics.vector.ImageVector
import ch.trailer.android.R
import ch.trailer.android.api.TourType

object MapLayers {

    fun overlayLayerId(type: TourType): String = when (type) {
        TourType.HIKING -> "swisstlm3d-wanderwege"
        TourType.BIKE -> "veloland"
        TourType.RUNNING -> "wanderland"
        TourType.MOUNTAIN_BIKE -> "mtbland"
        TourType.SKI -> "skitouren"
    }

    fun basemapLayerId(basemap: Basemap): String = when (basemap) {
        Basemap.SATELLITE -> "satellite"
        Basemap.TOPO -> "topo"
    }

    fun sportIcon(type: TourType): ImageVector = when (type) {
        TourType.HIKING -> Icons.Filled.Hiking
        TourType.BIKE -> Icons.Filled.DirectionsBike
        TourType.RUNNING -> Icons.Filled.DirectionsRun
        TourType.MOUNTAIN_BIKE -> Icons.Filled.PedalBike
        TourType.SKI -> Icons.Filled.DownhillSkiing
    }

    internal fun basemapPreview(basemap: Basemap): Int = when (basemap) {
        Basemap.SATELLITE -> R.drawable.basemap_satellite
        Basemap.TOPO -> R.drawable.basemap_topo
    }
}
