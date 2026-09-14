package ch.trailer.android.domain

import android.content.SharedPreferences
import ch.trailer.android.api.TourType

class SettingsStore(private val prefs: SharedPreferences) {

    var basemap: Basemap
        get() = parseBasemap(prefs.getString(KEY_BASEMAP, null))
        set(value) = prefs.edit().putString(KEY_BASEMAP, value.name).apply()

    var sport: TourType
        get() = parseTourType(prefs.getString(KEY_SPORT, null))
        set(value) = prefs.edit().putString(KEY_SPORT, value.name).apply()

    fun targetDistance(sport: TourType): Float =
        prefs.getFloat(distanceKey(sport), DEFAULT_DISTANCE)

    fun targetElevation(sport: TourType): Float =
        prefs.getFloat(elevationKey(sport), DEFAULT_ELEVATION)

    fun setTargets(sport: TourType, distance: Float, elevation: Float) {
        prefs.edit()
            .putFloat(distanceKey(sport), distance)
            .putFloat(elevationKey(sport), elevation)
            .apply()
    }

    companion object {
        private const val KEY_BASEMAP = "basemap"
        private const val KEY_SPORT = "sport_layer"
        private const val KEY_DISTANCE_PREFIX = "distance_"
        private const val KEY_ELEVATION_PREFIX = "elevation_"

        const val DEFAULT_DISTANCE = 10_000f
        const val DEFAULT_ELEVATION = 500f

        const val FILE_NAME = "settings"

        fun distanceKey(sport: TourType): String = KEY_DISTANCE_PREFIX + sport.name

        fun elevationKey(sport: TourType): String = KEY_ELEVATION_PREFIX + sport.name

        fun parseBasemap(name: String?): Basemap =
            name?.let { runCatching { Basemap.valueOf(it) }.getOrNull() } ?: Basemap.SATELLITE

        fun parseTourType(name: String?): TourType =
            name?.let { runCatching { TourType.valueOf(it) }.getOrNull() } ?: TourType.HIKING
    }
}
