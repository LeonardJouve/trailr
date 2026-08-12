package ch.trailer.android.api

class TrailRepository(private val api: TrailApi) {
    suspend fun findTrail(request: TrailRequest): TrailResponse {
        return api.findTrail(request)
    }
}
