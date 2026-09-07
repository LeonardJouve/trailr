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

    companion object {
        private const val KEY_BASEMAP = "basemap"
        private const val KEY_SPORT = "sport_layer"

        const val FILE_NAME = "settings"

        fun parseBasemap(name: String?): Basemap =
            name?.let { runCatching { Basemap.valueOf(it) }.getOrNull() } ?: Basemap.SATELLITE

        fun parseTourType(name: String?): TourType =
            name?.let { runCatching { TourType.valueOf(it) }.getOrNull() } ?: TourType.HIKING
    }
}
