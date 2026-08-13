package ch.trailer.android.api

import kotlinx.serialization.Serializable

@Serializable
data class GeoJsonFeature(
    val type: String,
    val geometry: GeoJsonMultiLineString
)

@Serializable
data class GeoJsonMultiLineString(
    val type: String,
    val coordinates: List<List<List<Double>>>
)

@Serializable
data class TrailResponse(
    val found: Boolean,
    val length: Double,
    val elevation: Double,
    val geoJSON: GeoJsonFeature
)