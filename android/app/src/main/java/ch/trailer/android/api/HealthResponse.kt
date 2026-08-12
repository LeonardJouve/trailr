package ch.trailer.android.api

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
)