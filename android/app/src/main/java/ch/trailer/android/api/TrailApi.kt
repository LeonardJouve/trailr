package ch.trailer.android.api

import retrofit2.http.Body
import retrofit2.http.POST

interface TrailApi {
    @POST("trail")
    suspend fun findTrail(@Body request: TrailRequest): TrailResponse
}