package com.example.data.network

import android.content.Context
import com.example.util.AppError
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
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
    data class RenderingOnGpu(val pollCount: Int = 0, val elapsedSeconds: Int = 0) : StylizeProgress()
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

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Short timeout client specifically for periodic background health checks
    private val healthClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private var activeCall: Call? = null
    @Volatile
    private var isCancelled = false

    private fun buildRetrofitApi(baseUrl: String): TitanAnimeApi {
        val cleanUrl = sanitizeUrl(baseUrl)
        val formattedBaseUrl = if (cleanUrl.endsWith("/")) cleanUrl else "$cleanUrl/"
        return Retrofit.Builder()
            .baseUrl(formattedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TitanAnimeApi::class.java)
    }

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
     * GET {BASE_URL}/ -> Verifies connection status
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

        val targetClient = if (isBackgroundPoll) healthClient else okHttpClient
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
     * Executes the 3-step asynchronous stylization sequence:
     * 1. UPLOAD: POST multipart to /stylize/start -> receives {"job_id": "...", "status": "processing"}
     * 2. POLL: GET /stylize/status/{job_id} every 5 seconds until "completed" or "failed"
     * 3. DOWNLOAD: GET /stylize/download/{job_id} -> streams video/mp4 binary to device
     */
    suspend fun stylizeVideo(
        context: Context,
        baseUrl: String,
        videoFile: File,
        mimeType: String = "video/mp4",
        onProgress: (StylizeProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        isCancelled = false
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

        // -------------------------------------------------------------
        // STEP 1: UPLOAD to /stylize/start
        // -------------------------------------------------------------
        val fileRequestBody = object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = totalFileLength

            override fun writeTo(sink: BufferedSink) {
                videoFile.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var totalWritten = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        if (isCancelled) throw IOException("Upload cancelled by user")
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

        val videoPart = MultipartBody.Part.createFormData(
            name = "video",
            filename = videoFile.name,
            body = fileRequestBody
        )

        val api = try {
            buildRetrofitApi(cleanUrl)
        } catch (e: Exception) {
            return@withContext Result.failure(AppError.InvalidUrl("Invalid server URL: ${e.message}"))
        }

        val jobId: String = try {
            val startResponse = api.startJob(videoPart)
            if (!startResponse.isSuccessful) {
                val errorBody = startResponse.errorBody()?.string()?.take(200) ?: "HTTP ${startResponse.code()}"
                return@withContext Result.failure(
                    AppError.ServerExecutionFailed(
                        statusCode = startResponse.code(),
                        userMessage = "Failed to start job (HTTP ${startResponse.code()}): $errorBody"
                    )
                )
            }

            val body = startResponse.body()
                ?: return@withContext Result.failure(AppError.ServerExecutionFailed(500, "Empty response from /stylize/start"))

            body.jobId
        } catch (e: Exception) {
            if (isCancelled) return@withContext Result.failure(AppError.OperationCancelled("Stylization cancelled by user."))
            return@withContext Result.failure(AppError.fromThrowable(e, "Upload to /stylize/start failed."))
        }

        if (isCancelled) {
            return@withContext Result.failure(AppError.OperationCancelled("Stylization cancelled by user."))
        }

        // -------------------------------------------------------------
        // STEP 2: POLL /stylize/status/{job_id} every 5 seconds
        // -------------------------------------------------------------
        var pollCounter = 0
        var isJobReadyForDownload = false

        onProgress(StylizeProgress.RenderingOnGpu(pollCount = 0, elapsedSeconds = 0))

        while (isActive && !isCancelled) {
            delay(5000L)
            if (isCancelled) break
            pollCounter++
            val elapsedSeconds = pollCounter * 5

            try {
                val statusResponse = api.getJobStatus(jobId)
                if (!statusResponse.isSuccessful) {
                    val code = statusResponse.code()
                    if (code == 404) {
                        return@withContext Result.failure(
                            AppError.ServerExecutionFailed(404, "Job $jobId not found on server.")
                        )
                    }
                    // For transient gateway/tunnel errors, continue polling up to max duration
                    continue
                }

                val statusBody = statusResponse.body() ?: continue
                when (statusBody.status.lowercase()) {
                    "processing" -> {
                        onProgress(
                            StylizeProgress.RenderingOnGpu(
                                pollCount = pollCounter,
                                elapsedSeconds = elapsedSeconds
                            )
                        )
                    }
                    "completed" -> {
                        isJobReadyForDownload = true
                        break
                    }
                    "failed" -> {
                        val failureMsg = statusBody.message ?: "Job failed on remote Colab GPU"
                        return@withContext Result.failure(
                            AppError.ServerExecutionFailed(
                                statusCode = 500,
                                userMessage = "GPU stylization failed: $failureMsg"
                            )
                        )
                    }
                    else -> {
                        onProgress(
                            StylizeProgress.RenderingOnGpu(
                                pollCount = pollCounter,
                                elapsedSeconds = elapsedSeconds
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (isCancelled) break
                // Keep polling if transient connection glitch
            }

            // Cap at 15 minutes of polling to avoid runaway loop
            if (elapsedSeconds >= 900) {
                return@withContext Result.failure(
                    AppError.Timeout("Job timed out after 15 minutes. Server did not finish processing.")
                )
            }
        }

        if (isCancelled) {
            return@withContext Result.failure(AppError.OperationCancelled("Stylization cancelled by user."))
        }

        if (!isJobReadyForDownload) {
            return@withContext Result.failure(AppError.ServerExecutionFailed(500, "Processing did not complete."))
        }

        // -------------------------------------------------------------
        // STEP 3: DOWNLOAD /stylize/download/{job_id}
        // -------------------------------------------------------------
        val timestamp = System.currentTimeMillis()
        val outputFile = File(context.cacheDir, "stylized_anime_$timestamp.mp4")

        try {
            val downloadResponse = api.downloadStylizedVideo(jobId)
            if (!downloadResponse.isSuccessful) {
                val code = downloadResponse.code()
                return@withContext Result.failure(
                    AppError.DownloadFailed("Failed to download result (HTTP $code)")
                )
            }

            val body = downloadResponse.body()
                ?: return@withContext Result.failure(AppError.DownloadFailed("Empty download stream from server."))

            val totalDownloadLength = body.contentLength()

            body.byteStream().use { inputStream ->
                FileOutputStream(outputFile).use { outputStream ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) throw IOException("Download cancelled by user")
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

            if (!outputFile.exists() || outputFile.length() == 0L) {
                if (outputFile.exists()) outputFile.delete()
                return@withContext Result.failure(
                    AppError.DownloadFailed("Downloaded video file is empty.")
                )
            }

            onProgress(StylizeProgress.Completed)
            Result.success(outputFile)
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            if (isCancelled) {
                Result.failure(AppError.OperationCancelled("Download cancelled by user."))
            } else {
                Result.failure(AppError.DownloadFailed("Failed to stream video: ${e.localizedMessage}"))
            }
        }
    }

    fun cancelActiveRequest() {
        isCancelled = true
        activeCall?.cancel()
        activeCall = null
    }
}
