package ch.trailer.android.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trails")
data class TrailEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val length: Double,
    val elevation: Double,
    val latitude: Double,
    val longitude: Double,
    val geoJSON: String,
    val createdAt: Long = System.currentTimeMillis()
)