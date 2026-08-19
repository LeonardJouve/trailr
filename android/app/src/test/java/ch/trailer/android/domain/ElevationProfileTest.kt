package ch.trailer.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevationProfileTest {

    @Test
    fun `parses multiLineString with elevation and computes cumulative distance`() {
        val geoJSON = """
            {
                "type": "Feature",
                "geometry": {
                    "type": "MultiLineString",
                    "coordinates": [
                        [
                            [8.0, 46.0, 500.0],
                            [8.0, 46.001, 550.0]
                        ],
                        [
                            [8.0, 46.001, 550.0],
                            [8.0, 46.002, 600.0]
                        ]
                    ]
                }
            }
        """.trimIndent()

        val profile = parseElevationProfile(geoJSON)

        assertEquals(3, profile.size)
        assertEquals(0.0, profile[0].distanceMeters, 0.1)
        assertEquals(500.0, profile[0].elevationMeters, 0.1)
        assertEquals(46.0, profile[0].latitude, 0.0001)
        assertEquals(8.0, profile[0].longitude, 0.0001)

        assertEquals(550.0, profile[1].elevationMeters, 0.1)
        assertEquals(600.0, profile[2].elevationMeters, 0.1)

        // Distance between 46.0 and 46.001 is roughly 111 meters.
        assertEquals(111.0, profile[1].distanceMeters, 5.0)
        assertEquals(222.0, profile[2].distanceMeters, 10.0)
    }

    @Test
    fun `falls back to zero elevation for two dimensional coordinates`() {
        val geoJSON = """
            {
                "type": "Feature",
                "geometry": {
                    "type": "MultiLineString",
                    "coordinates": [
                        [
                            [8.0, 46.0],
                            [8.0, 46.001]
                        ]
                    ]
                }
            }
        """.trimIndent()

        val profile = parseElevationProfile(geoJSON)

        assertEquals(2, profile.size)
        assertEquals(0.0, profile[0].elevationMeters, 0.1)
        assertEquals(0.0, profile[1].elevationMeters, 0.1)
    }

    @Test
    fun `returns empty list for empty multiLineString`() {
        val geoJSON = """
            {
                "type": "Feature",
                "geometry": {
                    "type": "MultiLineString",
                    "coordinates": []
                }
            }
        """.trimIndent()

        val profile = parseElevationProfile(geoJSON)

        assertEquals(0, profile.size)
    }

    @Test
    fun `returns single point for single coordinate`() {
        val geoJSON = """
            {
                "type": "Feature",
                "geometry": {
                    "type": "MultiLineString",
                    "coordinates": [
                        [
                            [8.0, 46.0, 500.0]
                        ]
                    ]
                }
            }
        """.trimIndent()

        val profile = parseElevationProfile(geoJSON)

        assertEquals(1, profile.size)
        assertEquals(0.0, profile[0].distanceMeters, 0.1)
        assertEquals(500.0, profile[0].elevationMeters, 0.1)
    }

    @Test
    fun `haversine returns expected distance for one degree of latitude`() {
        val distance = haversine(46.0, 8.0, 47.0, 8.0)

        assertEquals(111_194.9, distance, 100.0)
    }
}
