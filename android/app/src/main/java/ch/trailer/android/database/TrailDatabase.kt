package ch.trailer.android.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TrailEntity::class],
    version = 2
)
abstract class TrailDatabase : RoomDatabase() {
    abstract fun trailDao(): TrailDao
}