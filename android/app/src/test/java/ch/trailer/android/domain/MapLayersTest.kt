package ch.trailer.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayersTest {

    @Test
    fun satelliteShowsBrownContours() {
        assertEquals(
            listOf(
                "satellite",
                "contours-satellite",
                "contours-satellite-index",
                "contours-satellite-labels"
            ),
            MapLayers.basemapLayerIds(Basemap.SATELLITE)
        )
    }

    @Test
    fun topoShowsGreyContours() {
        assertEquals(
            listOf("topo", "contours-topo", "contours-topo-index", "contours-topo-labels"),
            MapLayers.basemapLayerIds(Basemap.TOPO)
        )
    }

    @Test
    fun basemapsShareNoLayers() {
        val satellite = MapLayers.basemapLayerIds(Basemap.SATELLITE).toSet()
        val topo = MapLayers.basemapLayerIds(Basemap.TOPO).toSet()

        assertTrue((satellite intersect topo).isEmpty())
    }
}
