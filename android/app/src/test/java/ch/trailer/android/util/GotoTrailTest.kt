package ch.trailer.android.util

import org.junit.Assert.assertEquals
import org.junit.Test

class GotoTrailTest {

    @Test
    fun buildsGeoUriPinnedAtThePoint() {
        val uri = GotoTrail.geoUri(46.123456, 8.123456, "Hike")

        assertEquals(
            "geo:46.123456,8.123456?q=46.123456,8.123456(Hike)",
            uri
        )
    }

    @Test
    fun formatsCoordinatesWithDotDecimalSeparator() {
        val uri = GotoTrail.geoUri(46.5, 8.25, "Bike")

        assertEquals(true, uri.startsWith("geo:46.500000,8.250000?"))
    }

    @Test
    fun percentEncodesSpacesAndSeparatorsInTheLabel() {
        val uri = GotoTrail.geoUri(46.0, 8.0, "Hike from 46.1 , 8.2")

        assertEquals(
            "geo:46.000000,8.000000?q=46.000000,8.000000" +
                "(Hike%20from%2046.1%20%2C%208.2)",
            uri
        )
    }
}
