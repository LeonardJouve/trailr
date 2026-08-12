package ch.trailer.android.api

import kotlinx.serialization.Serializable

@Serializable
data class TrailResponse(
    val found: Boolean,
    val length: Double,
    val elevation: Double,
    val nodeIds: List<Int>,
    val edgeIds: List<String>
)