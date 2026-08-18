package ch.trailer.android.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ch.trailer.android.SelectedPoint
import ch.trailer.android.OfflineMapManager
import ch.trailer.android.api.TrailRepository
import ch.trailer.android.api.TrailRequest
import ch.trailer.android.database.TrailEntity
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class TrailUiState(
    val isLoading: Boolean = false,
    val savedTrails: List<TrailEntity> = emptyList(),
    val selectedTrail: TrailEntity? = null,
    val error: String? = null
)

class TrailViewModel(
    private val repository: TrailRepository,
    private val offlineMapManager: OfflineMapManager
) : ViewModel() {
    var state by mutableStateOf(TrailUiState())
        private set

    init {
        observeSavedTrails()
    }

    private fun observeSavedTrails() {
        viewModelScope.launch {
            repository.getSavedTrails().collect { trails ->
                state = state.copy(savedTrails = trails)
            }
        }
    }

    fun healthCheck() {
        viewModelScope.launch {
            try {
                val response = repository.health()
                println("API health: ${response.status}")
            } catch (e: Exception) {
                println("API unavailable: ${e.message}")
            }
        }
    }

    fun findTrail(
        point: SelectedPoint,
        length: UInt,
        elevation: UInt
    ) {
        viewModelScope.launch {
            state = state.copy(
                isLoading = true,
                error = null
            )


            try {
                val result = repository.findTrail(
                    TrailRequest(
                        latitude = point.latitude,
                        longitude = point.longitude,
                        length = length,
                        elevation = elevation
                    )
                )

                println(Json.encodeToString(result.geoJSON))

                val start = result.geoJSON.geometry.coordinates[0][0]
                val trailId = "${point.latitude}_${point.longitude}_${System.currentTimeMillis()}"
                val trailEntity = TrailEntity(
                    id = trailId,
                    name = "Trail from ${"%.4f".format(java.util.Locale.US, point.latitude)} , ${"%.4f".format(java.util.Locale.US, point.longitude)}",
                    length = result.length,
                    elevation = result.elevation,
                    latitude = start[1],
                    longitude = start[0],
                    geoJSON = Json.encodeToString(result.geoJSON)
                )

                repository.saveTrail(trailEntity)

                state = state.copy(
                    isLoading = false,
                    selectedTrail = trailEntity
                )

                downloadTrailTiles(trailEntity)
            } catch (e: Exception) {
                println(e.message)
                state = state.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun deleteTrail(trail: TrailEntity) {
        viewModelScope.launch {
            try {
                offlineMapManager.deleteTrailTiles(trail)
            } catch (e: Exception) {
                println("Failed to delete offline tiles: ${e.message}")
            }

            try {
                repository.deleteTrail(trail)
            } catch (e: Exception) {
                state = state.copy(error = e.message)
            }
        }
    }

    fun selectTrail(trail: TrailEntity) {
        state = state.copy(selectedTrail = trail)

        viewModelScope.launch {
            try {
                if (!offlineMapManager.hasDownloadedTiles(trail)) {
                    offlineMapManager.downloadTrailTiles(trail)
                    println("Offline tiles downloaded for trail ${trail.id}")
                }
            } catch (e: Exception) {
                println("Failed to download offline tiles: ${e.message}")
            }
        }
    }

    fun clearSelectedTrail() {
        state = state.copy(selectedTrail = null)
    }

    private fun downloadTrailTiles(trail: TrailEntity) {
        viewModelScope.launch {
            try {
                offlineMapManager.downloadTrailTiles(trail)
                println("Offline tiles downloaded for trail ${trail.id}")
            } catch (e: Exception) {
                println("Failed to download offline tiles: ${e.message}")
            }
        }
    }
}

class TrailViewModelFactory(
    private val repository: TrailRepository,
    private val offlineMapManager: OfflineMapManager
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrailViewModel(repository, offlineMapManager) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class")
    }
}