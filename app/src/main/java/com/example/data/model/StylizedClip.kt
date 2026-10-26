package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stylized_clips")
data class StylizedClip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourceFileName: String,
    val localFilePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val fileSizeBytes: Long = 0,
    val serverUrl: String = ""
)
