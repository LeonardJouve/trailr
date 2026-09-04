package ch.trailer.android.api

import ch.trailer.android.database.TrailDao
import ch.trailer.android.database.TrailEntity
import kotlinx.coroutines.flow.Flow

class TrailRepository(private val api: TrailApi, private val dao: TrailDao) {
    suspend fun health(): HealthResponse {
        return api.health()
    }

    suspend fun findTour(type: TourType, request: TrailRequest): TrailResponse {
        return when (type) {
            TourType.HIKING -> api.findHikingTour(request)
            TourType.BIKE -> api.findBikeTour(request)
        }
    }

    suspend fun saveTrail(trail: TrailEntity) {
        dao.insert(trail)
    }

    fun getSavedTrails(): Flow<List<TrailEntity>> {
        return dao.getAll()
    }

    suspend fun getTrail(id: String): TrailEntity? {
        return dao.getById(id)
    }

    suspend fun deleteTrail(trail: TrailEntity) {
        dao.delete(trail)
    }
}
