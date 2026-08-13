package ch.trailer.android.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ch.trailer.android.SelectedPoint
import ch.trailer.android.api.TrailRepository
import ch.trailer.android.api.TrailRequest
import ch.trailer.android.api.TrailResponse
import ch.trailer.android.database.TrailEntity
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class TrailUiState(
    val isLoading: Boolean = false,
    val trail: TrailResponse? = null,
    val savedTrails: List<TrailEntity> = emptyList(),
    val error: String? = null
)

class TrailViewModel(
    private val repository: TrailRepository
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

                state = state.copy(
                    isLoading = false,
                    trail = result
                )
            } catch (e: Exception) {
                println(e.message)
                state = state.copy(
                    isLoading = false,
                    error = e.message
                )
            }
        }
    }

    fun saveTrail(
        id: String,
        name: String,
        trail: TrailResponse
    ) {
        viewModelScope.launch {
            try {
                val start = trail.geoJSON.geometry.coordinates[0][0]
                repository.saveTrail(
                    TrailEntity(
                        id = id,
                        name = name,
                        length = trail.length,
                        elevation = trail.elevation,
                        latitude = start[1],
                        longitude = start[0],
                        geoJSON = Json.encodeToString(trail.geoJSON)
                    )
                )
            } catch (e: Exception) {
                state = state.copy(error = e.message)
            }
        }
    }

    fun deleteTrail(trail: TrailEntity) {
        viewModelScope.launch {
            try {
                repository.deleteTrail(trail)
            } catch (e: Exception) {
                state = state.copy(error = e.message)
            }
        }
    }
}

class TrailViewModelFactory(
    private val repository: TrailRepository
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TrailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TrailViewModel(repository) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class")
    }
}