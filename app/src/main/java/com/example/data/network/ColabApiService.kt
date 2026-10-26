package com.example.data.network

import android.content.Context
import com.example.util.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

sealed class StylizeProgress {
    data class Compressing(val progressPercent: Int) : StylizeProgress()
    data class Uploading(val progressPercent: Int, val bytesWritten: Long, val totalBytes: Long) : StylizeProgress()
    object RenderingOnGpu : StylizeProgress()
    data class Downloading(val progressPercent: Int, val bytesRead: Long, val totalBytes: Long) : StylizeProgress()
    object Completed : StylizeProgress()
}

data class ServerHealth(
    val isOnline: Boolean,
    val statusCode: Int = 0,
    val message: String = "",
    val latencyMs: Long = 0,
    val checkedAt: Long = System.currentTimeMillis()
)

class ColabApiService {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Short timeout client specifically for periodic background health checks to avoid hanging
    private val healthClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var activeCall: Call? = null

    /**
     * Cleans and formats the base URL (ensures http/https and trims trailing slashes)
     */
    fun sanitizeUrl(rawUrl: String): String {
        var trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        return trimmed.trimEnd('/')
    }

    fun isValidUrl(url: String): Boolean {
        val sanitized = sanitizeUrl(url)
        return sanitized.startsWith("http://") || sanitized.startsWith("https://")
    }

    /**
     * GET {BASE_URL}/ -> Verifies connection status with comprehensive error handling
     */
    suspend fun checkServerHealth(baseUrl: String, isBackgroundPoll: Boolean = false): ServerHealth = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            return@withContext ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "Please enter a valid Colab server URL"
            )
        }

        val request = try {
            Request.Builder()
                .url("$cleanUrl/")
                .header("User-Agent", "TitanAnime-Android/1.0")
                .header("Accept", "*/*")
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "Malformed URL format: ${e.message}"
            )
        }

        val targetClient = if (isBackgroundPoll) healthClient else client
        val startTime = System.currentTimeMillis()

        try {
            targetClient.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                val bodySnippet = response.body?.string()?.take(150)?.trim() ?: ""
                val isSuccess = response.isSuccessful || response.code in 200..399

                ServerHealth(
                    isOnline = isSuccess,
                    statusCode = response.code,
                    message = if (isSuccess) {
                        if (bodySnippet.isNotBlank()) "Online: $bodySnippet" else "Connected (${response.code} OK)"
                    } else {
                        "Server returned HTTP ${response.code} (${response.message})"
                    },
                    latencyMs = latency,
                    checkedAt = System.currentTimeMillis()
                )
            }
        } catch (e: UnknownHostException) {
            ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "Host not found. Check if tunnel URL is active.",
                latencyMs = 0
            )
        } catch (e: ConnectException) {
            ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "Connection refused. Ensure the Colab notebook is running.",
                latencyMs = 0
            )
        } catch (e: SocketTimeoutException) {
            ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "Connection timed out. Server did not respond.",
                latencyMs = 0
            )
        } catch (e: SSLException) {
            ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = "SSL/TLS handshake error with tunnel: ${e.message}",
                latencyMs = 0
            )
        } catch (e: Exception) {
            ServerHealth(
                isOnline = false,
                statusCode = 0,
                message = e.localizedMessage ?: "Connection test failed",
                latencyMs = 0
            )
        }
    }

    /**
     * POST {BASE_URL}/stylize -> Uploads source video as multipart form-data (field name: video).
     * Streams back the completed MP4 binary directly to context.cacheDir with full error handling.
     */
    suspend fun stylizeVideo(
        context: Context,
        baseUrl: String,
        videoFile: File,
        mimeType: String = "video/mp4",
        onProgress: (StylizeProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeUrl(baseUrl)
        if (cleanUrl.isBlank()) {
            return@withContext Result.failure(AppError.InvalidUrl())
        }

        if (!videoFile.exists() || videoFile.length() == 0L) {
            return@withContext Result.failure(
                AppError.FileAccessError("Source video file is missing or has 0 bytes.")
            )
        }

        val totalFileLength = videoFile.length()
        val mediaType = mimeType.toMediaTypeOrNull()

        // Create file request body with progress tracking
        val baseFileBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = totalFileLength

            override fun writeTo(sink: BufferedSink) {
                videoFile.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var totalWritten = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        sink.write(buffer, 0, read)
                        totalWritten += read
                        val percent = if (totalFileLength > 0) {
                            ((totalWritten * 100) / totalFileLength).toInt().coerceIn(0, 100)
                        } else 0

                        onProgress(
                            StylizeProgress.Uploading(
                                progressPercent = percent,
                                bytesWritten = totalWritten,
                                totalBytes = totalFileLength
                            )
                        )
                    }
                }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "video",
                filename = videoFile.name,
                body = baseFileBody
            )
            .build()

        val request = try {
            Request.Builder()
                .url("$cleanUrl/stylize")
                .header("User-Agent", "TitanAnime-Android/1.0")
                .post(multipartBody)
                .build()
        } catch (e: Exception) {
            return@withContext Result.failure(AppError.InvalidUrl("Invalid stylize endpoint URL: ${e.message}"))
        }

        val call = client.newCall(request)
        activeCall = call

        val timestamp = System.currentTimeMillis()
        val outputFile = File(context.cacheDir, "stylized_anime_$timestamp.mp4")

        try {
            // Signal transition to GPU rendering once request has finished uploading
            val response = call.execute()
            onProgress(StylizeProgress.RenderingOnGpu)

            if (!response.isSuccessful) {
                val errorMsg = try {
                    response.body?.string()?.take(300) ?: "HTTP ${response.code}"
                } catch (_: Exception) {
                    "HTTP ${response.code}"
                }

                val userMessage = when (response.code) {
                    404 -> "Endpoint /stylize not found (HTTP 404). Check backend script route."
                    413 -> "Payload too large (HTTP 413). Try compressing video first."
                    500, 502, 503 -> "Colab GPU server error (HTTP ${response.code}): $errorMsg"
                    else -> "Stylization failed (HTTP ${response.code}): $errorMsg"
                }

                return@withContext Result.failure(
                    AppError.ServerExecutionFailed(statusCode = response.code, userMessage = userMessage)
                )
            }

            val responseBody = response.body
                ?: return@withContext Result.failure(AppError.DownloadFailed("Server returned an empty response body."))

            val totalDownloadLength = responseBody.contentLength()

            // Stream response directly to cache file
            try {
                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val percent = if (totalDownloadLength > 0) {
                                ((totalRead * 100) / totalDownloadLength).toInt().coerceIn(0, 100)
                            } else -1

                            onProgress(
                                StylizeProgress.Downloading(
                                    progressPercent = percent,
                                    bytesRead = totalRead,
                                    totalBytes = totalDownloadLength
                                )
                            )
                        }
                        outputStream.flush()
                    }
                }
            } catch (e: Exception) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext Result.failure(
                    AppError.DownloadFailed("Interrupted while downloading MP4 stream: ${e.localizedMessage}")
                )
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext Result.failure(
                    AppError.DownloadFailed("Downloaded video file is empty or corrupted.")
                )
            }

            onProgress(StylizeProgress.Completed)
            Result.success(outputFile)
        } catch (e: SocketTimeoutException) {
            if (outputFile.exists()) outputFile.delete()
            Result.failure(
                AppError.Timeout("Operation timed out after 300s. The Colab GPU backend took too long to complete rendering.")
            )
        } catch (e: UnknownHostException) {
            if (outputFile.exists()) outputFile.delete()
            Result.failure(
                AppError.ServerUnreachable("Host unreachable. Tunnel may have expired or disconnected.", e)
            )
        } catch (e: ConnectException) {
            if (outputFile.exists()) outputFile.delete()
            Result.failure(
                AppError.ServerUnreachable("Connection lost. Remote Colab notebook stopped responding.", e)
            )
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            Result.failure(AppError.fromThrowable(e, "Stylization request failed."))
        } finally {
            activeCall = null
        }
    }

    fun cancelActiveRequest() {
        activeCall?.cancel()
        activeCall = null
    }
}
