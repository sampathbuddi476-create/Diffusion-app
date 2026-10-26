package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.StylizedClip
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Query("SELECT * FROM stylized_clips ORDER BY createdAt DESC")
    fun getAllClips(): Flow<List<StylizedClip>>

    @Query("SELECT * FROM stylized_clips WHERE id = :id LIMIT 1")
    suspend fun getClipById(id: Long): StylizedClip?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClip(clip: StylizedClip): Long

    @Delete
    suspend fun deleteClip(clip: StylizedClip)

    @Query("DELETE FROM stylized_clips WHERE id = :id")
    suspend fun deleteClipById(id: Long)

    @Query("DELETE FROM stylized_clips")
    suspend fun clearAllClips()
}
