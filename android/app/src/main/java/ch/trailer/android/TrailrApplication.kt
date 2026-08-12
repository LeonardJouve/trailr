package ch.trailer.android

import android.app.Application
import org.maplibre.android.MapLibre

class TrailrApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        MapLibre.getInstance(this)
    }
}