package ch.trailer.android.api

import kotlinx.serialization.Serializable

@Serializable
data class TrailRequest(
    val latitude: Double,
    val longitude: Double,
    val length: UInt,
    val elevation: UInt
)