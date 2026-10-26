package com.example.util

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CompressionQuality(
    val label: String,
    val description: String,
    val targetHeight: Int?,
    val estimatedSavings: String
) {
    ORIGINAL("Original", "No re-encoding (full resolution)", null, "0% size reduction"),
    HIGH("High Quality (720p)", "Crisp anime details with balanced size", 720, "~35% size reduction"),
    MEDIUM("Medium Quality (540p)", "Recommended: fast GPU frame inference", 540, "~60% size reduction"),
    LOW("Fast Mode (360p)", "Smallest file, fastest Colab rendering", 360, "~80% size reduction")
}

data class CompressionResult(
    val compressedFile: File,
    val originalSize: Long,
    val compressedSize: Long,
    val quality: CompressionQuality,
    val wasCompressed: Boolean
)

@OptIn(UnstableApi::class)
class VideoCompressor(private val context: Context) {

    private var activeTransformer: Transformer? = null

    /**
     * Compresses the input video according to the chosen quality setting.
     * Returns the output file (or original file if ORIGINAL quality was selected or fallback occurred).
     */
    suspend fun compressVideo(
        inputFile: File,
        quality: CompressionQuality,
        onProgress: (percent: Int) -> Unit
    ): Result<CompressionResult> = withContext(Dispatchers.Main) {
        val originalSize = inputFile.length()

        // If no compression requested, return original directly
        if (quality == CompressionQuality.ORIGINAL || quality.targetHeight == null) {
            return@withContext Result.success(
                CompressionResult(
                    compressedFile = inputFile,
                    originalSize = originalSize,
                    compressedSize = originalSize,
                    quality = quality,
                    wasCompressed = false
                )
            )
        }

        val outputDir = File(context.cacheDir, "compressed_videos").apply { mkdirs() }
        val outputFile = File(
            outputDir,
            "compressed_${quality.name.lowercase()}_${System.currentTimeMillis()}.mp4"
        )

        try {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(inputFile))

            val scaleFactor = quality.targetHeight.toFloat() / 1080f
            val scaleAndRotateTransformation = ScaleAndRotateTransformation.Builder()
                .setScale(scaleFactor, scaleFactor)
                .build()
            val videoEffects = listOf(scaleAndRotateTransformation)

            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(emptyList(), videoEffects))
                .setRemoveAudio(false)
                .build()

            suspendCancellableCoroutine<ExportResult> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) {
                                continuation.resume(exportResult)
                            }
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(exportException)
                            }
                        }
                    })
                    .build()

                activeTransformer = transformer

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    activeTransformer = null
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                }

                transformer.start(editedMediaItem, outputFile.absolutePath)
            }

            activeTransformer = null

            val compressedSize = outputFile.length()
            if (outputFile.exists() && compressedSize > 0) {
                onProgress(100)
                Result.success(
                    CompressionResult(
                        compressedFile = outputFile,
                        originalSize = originalSize,
                        compressedSize = compressedSize,
                        quality = quality,
                        wasCompressed = true
                    )
                )
            } else {
                // Fallback to original
                Result.success(
                    CompressionResult(
                        compressedFile = inputFile,
                        originalSize = originalSize,
                        compressedSize = originalSize,
                        quality = CompressionQuality.ORIGINAL,
                        wasCompressed = false
                    )
                )
            }
        } catch (e: Exception) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            activeTransformer = null
            Result.failure(e)
        }
    }

    fun cancel() {
        activeTransformer?.cancel()
        activeTransformer = null
    }
}
