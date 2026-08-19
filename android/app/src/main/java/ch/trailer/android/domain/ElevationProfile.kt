package ch.trailer.android.domain

import ch.trailer.android.api.GeoJsonFeature
import kotlinx.serialization.json.Json
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class ElevationPoint(
    val distanceMeters: Double,
    val elevationMeters: Double,
    val latitude: Double,
    val longitude: Double
)

private const val EARTH_RADIUS_METERS = 6_371_000.0

internal fun haversine(
    lat1: Double,
    lon1: Double,
    lat2: Double,
    lon2: Double
): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) *
        cos(Math.toRadians(lat2)) *
        sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
}

fun parseElevationProfile(geoJSON: String): List<ElevationPoint> {
    val feature = Json.decodeFromString<GeoJsonFeature>(geoJSON)
    val lines = feature.geometry.coordinates

    if (lines.isEmpty()) {
        return emptyList()
    }

    val points = mutableListOf<ElevationPoint>()
    var cumulativeDistance = 0.0

    lines.forEachIndexed { lineIndex, line ->
        line.forEachIndexed { pointIndex, rawPoint ->
            // MultiLineString segments share endpoints; skip duplicated starts.
            if (lineIndex > 0 && pointIndex == 0) {
                return@forEachIndexed
            }

            val longitude = rawPoint.getOrNull(0) ?: return@forEachIndexed
            val latitude = rawPoint.getOrNull(1) ?: return@forEachIndexed
            val elevation = rawPoint.getOrNull(2) ?: 0.0

            if (lineIndex == 0 && pointIndex == 0) {
                points.add(
                    ElevationPoint(
                        distanceMeters = 0.0,
                        elevationMeters = elevation,
                        latitude = latitude,
                        longitude = longitude
                    )
                )
                return@forEachIndexed
            }

            val previous = points.last()
            val segmentDistance = haversine(
                previous.latitude,
                previous.longitude,
                latitude,
                longitude
            )
            cumulativeDistance += segmentDistance

            points.add(
                ElevationPoint(
                    distanceMeters = cumulativeDistance,
                    elevationMeters = elevation,
                    latitude = latitude,
                    longitude = longitude
                )
            )
        }
    }

    return points
}
