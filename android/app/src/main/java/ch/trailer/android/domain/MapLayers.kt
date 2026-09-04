package ch.trailer.android.domain

import ch.trailer.android.api.TourType

/**
 * Map layer ids used by the swisstopo style.
 *
 * The satellite raster is always visible; each tour type overlays
 * its swisstopo trail network on top.
 */
object MapLayers {

    fun overlayLayerId(type: TourType): String = when (type) {
        TourType.HIKING -> "swisstlm3d-wanderwege"
        TourType.BIKE -> "veloland"
    }
}
