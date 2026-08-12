package ch.trailer.android.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface TrailApi {
    @GET("healthcheck")
    suspend fun health(): HealthResponse
    @POST("trail")
    suspend fun findTrail(@Body request: TrailRequest): TrailResponse
}