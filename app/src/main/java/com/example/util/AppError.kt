package com.example.util

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

sealed class AppError(
    open val userMessage: String,
    override val cause: Throwable? = null
) : Exception(userMessage, cause) {

    data class InvalidUrl(
        override val userMessage: String = "Invalid server URL. Please enter a valid URL (e.g. https://xxxx.trycloudflare.com)"
    ) : AppError(userMessage)

    data class ServerUnreachable(
        override val userMessage: String = "Colab server unreachable. Check that your tunnel is running and URL is correct.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class Timeout(
        override val userMessage: String = "Request timed out. The Colab GPU backend took too long to complete.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class UploadFailed(
        override val userMessage: String = "Failed to upload video clip. Check network connection.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class ServerExecutionFailed(
        val statusCode: Int,
        override val userMessage: String = "GPU rendering failed on Colab backend (HTTP $statusCode).",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class DownloadFailed(
        override val userMessage: String = "Failed to download stylized MP4 result.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class CompressionFailed(
        override val userMessage: String = "Video compression encountered an issue. Using original clip instead.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class StorageError(
        override val userMessage: String = "Failed to save video to Gallery. Please verify device storage space.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class FileAccessError(
        override val userMessage: String = "Could not read the selected video file.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    data class Unknown(
        override val userMessage: String = "An unexpected error occurred.",
        override val cause: Throwable? = null
    ) : AppError(userMessage, cause)

    companion object {
        fun fromThrowable(t: Throwable, defaultMessage: String = "Operation failed"): AppError {
            if (t is AppError) return t
            return when (t) {
                is UnknownHostException -> ServerUnreachable(
                    userMessage = "Host not found. Check if the Cloudflare/Ngrok tunnel URL is active.",
                    cause = t
                )
                is SocketTimeoutException -> Timeout(
                    userMessage = "Operation timed out. Colab GPU backend may be overwhelmed or offline.",
                    cause = t
                )
                is ConnectException -> ServerUnreachable(
                    userMessage = "Connection refused. Ensure the Colab notebook web server is running.",
                    cause = t
                )
                is SSLException -> ServerUnreachable(
                    userMessage = "SSL/TLS handshake error. Ensure your tunnel uses valid HTTPS.",
                    cause = t
                )
                is IOException -> Network(
                    userMessage = t.localizedMessage ?: "Network I/O error occurred.",
                    cause = t
                )
                else -> Unknown(
                    userMessage = t.localizedMessage?.takeIf { it.isNotBlank() } ?: defaultMessage,
                    cause = t
                )
            }
        }
    }
}

data class Network(
    override val userMessage: String,
    override val cause: Throwable? = null
) : AppError(userMessage, cause)
