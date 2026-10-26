package com.example.data.repository

import com.example.data.local.ClipDao
import com.example.data.model.StylizedClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ClipRepository(private val clipDao: ClipDao) {

    val allClips: Flow<List<StylizedClip>> = clipDao.getAllClips()

    suspend fun insertClip(clip: StylizedClip): Long = withContext(Dispatchers.IO) {
        clipDao.insertClip(clip)
    }

    suspend fun getClipById(id: Long): StylizedClip? = withContext(Dispatchers.IO) {
        clipDao.getClipById(id)
    }

    suspend fun deleteClip(clip: StylizedClip) = withContext(Dispatchers.IO) {
        // Also remove local file if it exists in cache
        try {
            val file = File(clip.localFilePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (_: Exception) {}
        clipDao.deleteClip(clip)
    }

    suspend fun deleteClipById(id: Long) = withContext(Dispatchers.IO) {
        val clip = clipDao.getClipById(id)
        if (clip != null) {
            deleteClip(clip)
        }
    }
}
