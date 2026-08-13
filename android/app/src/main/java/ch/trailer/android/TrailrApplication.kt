package ch.trailer.android

import android.app.Application
import androidx.room.Room
import ch.trailer.android.database.TrailDatabase
import org.maplibre.android.MapLibre

class TrailrApplication : Application() {
    val database: TrailDatabase by lazy {
        Room.databaseBuilder(
            this,
            TrailDatabase::class.java,
            "trails.db"
        ).build()
    }

    override fun onCreate() {
        super.onCreate()

        MapLibre.getInstance(this)
    }
}