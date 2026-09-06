package ch.trailer.android.domain

import ch.trailer.android.api.TourType

object MapLayers {

    fun overlayLayerId(type: TourType): String = when (type) {
        TourType.HIKING -> "swisstlm3d-wanderwege"
        TourType.BIKE -> "veloland"
        TourType.RUNNING -> "wanderland"
        TourType.MOUNTAIN_BIKE -> "mtbland"
        TourType.SKI -> "skitouren"
    }
}
