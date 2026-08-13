package ch.trailer.android.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrailDao {

    @Query("SELECT * FROM trails ORDER BY createdAt DESC")
    fun getAll(): Flow<List<TrailEntity>>

    @Query("SELECT * FROM trails WHERE id = :id")
    suspend fun getById(id: String): TrailEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(trail: TrailEntity)

    @Delete
    suspend fun delete(trail: TrailEntity)
}