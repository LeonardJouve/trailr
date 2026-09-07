package ch.trailer.android.util

import java.net.URLEncoder
import java.util.Locale

object GotoTrail {
    fun geoUri(latitude: Double, longitude: Double, label: String): String {
        val encodedLabel = URLEncoder.encode(label, "UTF-8").replace("+", "%20")
        return String.format(
            Locale.US,
            "geo:%.6f,%.6f?q=%.6f,%.6f(%s)",
            latitude,
            longitude,
            latitude,
            longitude,
            encodedLabel
        )
    }
}
