package com.example.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class SelectedVideoInfo(
    val uri: Uri,
    val fileName: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val localFile: File? = null
)

object VideoUtils {

    /**
     * Resolves metadata and creates a local working file from a content Uri with full error safety
     */
    suspend fun resolveVideoInfo(context: Context, uri: Uri): Result<SelectedVideoInfo> = withContext(Dispatchers.IO) {
        try {
            var name = "video_${System.currentTimeMillis()}.mp4"
            var size: Long = 0

            // Query ContentResolver for name and size
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            cursor.getString(nameIndex)?.let { name = it }
                        }
                        if (sizeIndex != -1) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {}

            // Copy uri stream to a cache file so we have direct file access for OkHttp / Transformer
            val tempInputFile = File(context.cacheDir, "source_input_${System.currentTimeMillis()}.mp4")
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext Result.failure(
                        AppError.FileAccessError("Unable to open input stream for selected video.")
                    )

                inputStream.use { input ->
                    FileOutputStream(tempInputFile).use { output ->
                        input.copyTo(output)
                    }
                }

                if (size == 0L && tempInputFile.exists()) {
                    size = tempInputFile.length()
                }
            } catch (e: Exception) {
                return@withContext Result.failure(
                    AppError.FileAccessError("Failed to cache video: ${e.localizedMessage}")
                )
            }

            if (!tempInputFile.exists() || tempInputFile.length() == 0L) {
                return@withContext Result.failure(
                    AppError.FileAccessError("Selected video file is empty or corrupted (0 bytes).")
                )
            }

            // Query duration via MediaMetadataRetriever
            var durationMs: Long = 0
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempInputFile.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                durationMs = durationStr?.toLongOrNull() ?: 0L
            } catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }

            Result.success(
                SelectedVideoInfo(
                    uri = uri,
                    fileName = name,
                    durationMs = durationMs,
                    fileSizeBytes = size,
                    localFile = tempInputFile
                )
            )
        } catch (e: Exception) {
            Result.failure(AppError.fromThrowable(e, "Could not load video clip"))
        }
    }

    /**
     * Saves the stylized MP4 video file to the public Gallery via Android MediaStore API
     */
    suspend fun saveVideoToGallery(context: Context, videoFile: File): Result<Uri> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext Result.failure(
                AppError.StorageError("Video file to save does not exist or is empty.")
            )
        }

        try {
            val filename = "TitanAnime_${System.currentTimeMillis()}.mp4"
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                put(MediaStore.Video.Media.TITLE, filename)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TitanAnime")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val contentResolver = context.contentResolver
            val itemUri = contentResolver.insert(collection, contentValues)
                ?: return@withContext Result.failure(
                    AppError.StorageError("Failed to allocate MediaStore entry in Gallery.")
                )

            try {
                contentResolver.openOutputStream(itemUri).use { outputStream ->
                    if (outputStream == null) {
                        throw IllegalStateException("Failed to open output stream for gallery storage.")
                    }
                    videoFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    outputStream.flush()
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    contentResolver.update(itemUri, contentValues, null, null)
                }

                Result.success(itemUri)
            } catch (e: Exception) {
                try {
                    contentResolver.delete(itemUri, null, null)
                } catch (_: Exception) {}
                Result.failure(AppError.StorageError("Error copying video to gallery: ${e.localizedMessage}", e))
            }
        } catch (e: Exception) {
            Result.failure(AppError.StorageError("Unable to access MediaStore: ${e.localizedMessage}", e))
        }
    }

    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "00:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.getDefault(), "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
            else -> "$bytes B"
        }
    }
}
