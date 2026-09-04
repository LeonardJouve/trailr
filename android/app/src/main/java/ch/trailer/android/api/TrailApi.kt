package ch.trailer.android.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface TrailApi {
    @GET("healthcheck")
    suspend fun health(): HealthResponse

    @POST("hiking-tour")
    suspend fun findHikingTour(@Body request: TrailRequest): TrailResponse

    @POST("bike-tour")
    suspend fun findBikeTour(@Body request: TrailRequest): TrailResponse
}