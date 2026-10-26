package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.StylizedClip
import com.example.data.network.ColabApiService
import com.example.data.network.ServerHealth
import com.example.data.network.StylizeProgress
import com.example.data.repository.ClipRepository
import com.example.util.AppError
import com.example.util.CompressionQuality
import com.example.util.SelectedVideoInfo
import com.example.util.VideoCompressor
import com.example.util.VideoUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed class ServerConnectionState {
    object Disconnected : ServerConnectionState()
    object Checking : ServerConnectionState()
    data class Connected(
        val latencyMs: Long,
        val message: String,
        val lastCheckedAt: Long = System.currentTimeMillis()
    ) : ServerConnectionState()
    data class Error(
        val message: String,
        val lastCheckedAt: Long = System.currentTimeMillis()
    ) : ServerConnectionState()
}

enum class PollingInterval(val label: String, val minutes: Int) {
    MINUTES_1("1 min", 1),
    MINUTES_3("3 min", 3),
    MINUTES_5("5 min (Default)", 5),
    MINUTES_10("10 min", 10),
    OFF("Off", 0)
}

sealed class ProcessingState {
    object Idle : ProcessingState()
    data class InProgress(
        val stage: StylizeProgress,
        val stageDescription: String,
        val progressPercent: Float?
    ) : ProcessingState()
    data class Success(val clip: StylizedClip) : ProcessingState()
    data class Failed(val error: String) : ProcessingState()
}

sealed class UiEvent {
    data class ShowSnackbar(
        val message: String,
        val isError: Boolean = false,
        val actionLabel: String? = null
    ) : UiEvent()
    data class ShowToast(val message: String) : UiEvent()
}

data class TitanAnimeUiState(
    val serverUrl: String = "",
    val connectionState: ServerConnectionState = ServerConnectionState.Disconnected,
    val pollingInterval: PollingInterval = PollingInterval.MINUTES_5,
    val selectedVideo: SelectedVideoInfo? = null,
    val compressionQuality: CompressionQuality = CompressionQuality.MEDIUM,
    val isResolvingVideo: Boolean = false,
    val processingState: ProcessingState = ProcessingState.Idle,
    val activeResultClip: StylizedClip? = null,
    val isSavingToGallery: Boolean = false,
    val isGallerySaved: Boolean = false,
    val lastPollTimestamp: Long = 0L
)

class TitanAnimeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("titan_anime_prefs", Context.MODE_PRIVATE)
    private val PREF_KEY_SERVER_URL = "saved_server_url"
    private val PREF_KEY_POLL_INTERVAL = "saved_poll_interval_minutes"
    private val PREF_KEY_COMPRESSION_QUALITY = "saved_compression_quality"

    private val apiService = ColabApiService()
    private val repository = ClipRepository(AppDatabase.getDatabase(application).clipDao())
    private val videoCompressor = VideoCompressor(application)

    private var pollingJob: Job? = null
    private var activeStylizeJob: Job? = null

    val cachedClips: StateFlow<List<StylizedClip>> = repository.allClips
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(
        TitanAnimeUiState(
            serverUrl = prefs.getString(PREF_KEY_SERVER_URL, "") ?: "",
            pollingInterval = loadSavedPollingInterval(),
            compressionQuality = loadSavedCompressionQuality()
        )
    )
    val uiState: StateFlow<TitanAnimeUiState> = _uiState.asStateFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>()
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    init {
        val savedUrl = _uiState.value.serverUrl
        if (savedUrl.isNotBlank()) {
            checkConnection(isInitial = true)
        }
        startBackgroundHealthPolling()
    }

    private fun loadSavedPollingInterval(): PollingInterval {
        val minutes = prefs.getInt(PREF_KEY_POLL_INTERVAL, 5)
        return PollingInterval.values().find { it.minutes == minutes } ?: PollingInterval.MINUTES_5
    }

    private fun loadSavedCompressionQuality(): CompressionQuality {
        val name = prefs.getString(PREF_KEY_COMPRESSION_QUALITY, CompressionQuality.MEDIUM.name)
        return try {
            CompressionQuality.valueOf(name ?: CompressionQuality.MEDIUM.name)
        } catch (_: Exception) {
            CompressionQuality.MEDIUM
        }
    }

    fun onServerUrlChange(newUrl: String) {
        _uiState.update {
            it.copy(
                serverUrl = newUrl,
                connectionState = if (it.connectionState is ServerConnectionState.Connected && it.serverUrl != newUrl)
                    ServerConnectionState.Disconnected else it.connectionState
            )
        }
    }

    fun setPollingInterval(interval: PollingInterval) {
        prefs.edit().putInt(PREF_KEY_POLL_INTERVAL, interval.minutes).apply()
        _uiState.update { it.copy(pollingInterval = interval) }
        startBackgroundHealthPolling()
        viewModelScope.launch {
            _uiEvents.emit(
                UiEvent.ShowSnackbar(
                    message = if (interval == PollingInterval.OFF)
                        "Background health polling disabled"
                    else
                        "Background health polling set to every ${interval.minutes} min"
                )
            )
        }
    }

    fun setCompressionQuality(quality: CompressionQuality) {
        prefs.edit().putString(PREF_KEY_COMPRESSION_QUALITY, quality.name).apply()
        _uiState.update { it.copy(compressionQuality = quality) }
        viewModelScope.launch {
            _uiEvents.emit(
                UiEvent.ShowSnackbar("Compression preset: ${quality.label} (${quality.estimatedSavings})")
            )
        }
    }

    fun checkConnection(isInitial: Boolean = false) {
        val rawUrl = _uiState.value.serverUrl
        val cleanUrl = apiService.sanitizeUrl(rawUrl)

        if (cleanUrl.isBlank()) {
            _uiState.update {
                it.copy(connectionState = ServerConnectionState.Error("Please enter a Colab server URL"))
            }
            viewModelScope.launch {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar(
                        message = "Please enter a valid Colab tunnel URL (e.g. https://...trycloudflare.com)",
                        isError = true
                    )
                )
            }
            return
        }

        prefs.edit().putString(PREF_KEY_SERVER_URL, cleanUrl).apply()
        _uiState.update {
            it.copy(
                serverUrl = cleanUrl,
                connectionState = ServerConnectionState.Checking
            )
        }

        viewModelScope.launch {
            try {
                val health: ServerHealth = apiService.checkServerHealth(cleanUrl, isBackgroundPoll = false)
                if (health.isOnline) {
                    _uiState.update {
                        it.copy(
                            connectionState = ServerConnectionState.Connected(
                                latencyMs = health.latencyMs,
                                message = health.message,
                                lastCheckedAt = health.checkedAt
                            ),
                            lastPollTimestamp = health.checkedAt
                        )
                    }
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar("Connected to Colab GPU (${health.latencyMs}ms)")
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            connectionState = ServerConnectionState.Error(
                                message = health.message,
                                lastCheckedAt = health.checkedAt
                            ),
                            lastPollTimestamp = health.checkedAt
                        )
                    }
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar(
                            message = "Connection failed: ${health.message}",
                            isError = true
                        )
                    )
                }
            } catch (e: Exception) {
                val appError = AppError.fromThrowable(e, "Connection failed")
                _uiState.update {
                    it.copy(
                        connectionState = ServerConnectionState.Error(
                            message = appError.userMessage,
                            lastCheckedAt = System.currentTimeMillis()
                        )
                    )
                }
                _uiEvents.emit(
                    UiEvent.ShowSnackbar(
                        message = appError.userMessage,
                        isError = true
                    )
                )
            }
        }
    }

    /**
     * Coroutine to periodically poll the Colab backend's health check endpoint (GET {BASE_URL}/)
     * Configurable interval, updates status dot in real-time.
     */
    private fun startBackgroundHealthPolling() {
        pollingJob?.cancel()
        val intervalMinutes = _uiState.value.pollingInterval.minutes
        if (intervalMinutes <= 0) return

        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            val intervalMillis = intervalMinutes * 60 * 1000L
            while (isActive) {
                delay(intervalMillis)
                val url = _uiState.value.serverUrl
                val cleanUrl = apiService.sanitizeUrl(url)

                if (cleanUrl.isNotBlank() && _uiState.value.processingState !is ProcessingState.InProgress) {
                    try {
                        val health = apiService.checkServerHealth(cleanUrl, isBackgroundPoll = true)
                        if (health.isOnline) {
                            _uiState.update {
                                it.copy(
                                    connectionState = ServerConnectionState.Connected(
                                        latencyMs = health.latencyMs,
                                        message = health.message,
                                        lastCheckedAt = health.checkedAt
                                    ),
                                    lastPollTimestamp = health.checkedAt
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    connectionState = ServerConnectionState.Error(
                                        message = health.message,
                                        lastCheckedAt = health.checkedAt
                                    ),
                                    lastPollTimestamp = health.checkedAt
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun onVideoSelected(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isResolvingVideo = true) }
            val result = VideoUtils.resolveVideoInfo(getApplication(), uri)

            result.fold(
                onSuccess = { videoInfo ->
                    _uiState.update {
                        it.copy(
                            selectedVideo = videoInfo,
                            isResolvingVideo = false
                        )
                    }
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar(
                            "Loaded: ${videoInfo.fileName} (${VideoUtils.formatDuration(videoInfo.durationMs)}, ${VideoUtils.formatFileSize(videoInfo.fileSizeBytes)})"
                        )
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isResolvingVideo = false) }
                    val message = error.localizedMessage ?: "Could not open selected video"
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar(
                            message = "Error loading video: $message",
                            isError = true
                        )
                    )
                }
            )
        }
    }

    fun clearSelectedVideo() {
        _uiState.value.selectedVideo?.localFile?.delete()
        _uiState.update { it.copy(selectedVideo = null) }
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Video clip removed"))
        }
    }

    fun stylizeVideo() {
        val currentState = _uiState.value
        val isOnline = currentState.connectionState is ServerConnectionState.Connected
        val video = currentState.selectedVideo
        val serverUrl = currentState.serverUrl
        val compression = currentState.compressionQuality

        if (!isOnline) {
            viewModelScope.launch {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar("Colab backend is not connected. Connect first.", isError = true)
                )
            }
            return
        }

        if (video == null || video.localFile == null || !video.localFile.exists()) {
            viewModelScope.launch {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar("Please select a video clip to stylize.", isError = true)
                )
            }
            return
        }

        activeStylizeJob?.cancel()
        activeStylizeJob = viewModelScope.launch {
            var uploadFile = video.localFile

            // Step 1: Optional Client-Side Compression
            if (compression != CompressionQuality.ORIGINAL) {
                _uiState.update {
                    it.copy(
                        processingState = ProcessingState.InProgress(
                            stage = StylizeProgress.Compressing(0),
                            stageDescription = "Compressing video (${compression.label})... 0%",
                            progressPercent = 0.05f
                        ),
                        isGallerySaved = false
                    )
                }

                val compResult = videoCompressor.compressVideo(
                    inputFile = video.localFile,
                    quality = compression,
                    onProgress = { percent ->
                        _uiState.update {
                            it.copy(
                                processingState = ProcessingState.InProgress(
                                    stage = StylizeProgress.Compressing(percent),
                                    stageDescription = "Compressing video (${compression.label})... $percent%",
                                    progressPercent = (percent / 100f) * 0.2f
                                )
                            )
                        }
                    }
                )

                compResult.fold(
                    onSuccess = { res ->
                        if (res.wasCompressed) {
                            uploadFile = res.compressedFile
                            _uiEvents.emit(
                                UiEvent.ShowToast(
                                    "Compressed to ${VideoUtils.formatFileSize(res.compressedSize)} (${compression.estimatedSavings})"
                                )
                            )
                        }
                    },
                    onFailure = { compError ->
                        // Graceful fallback to original file
                        _uiEvents.emit(
                            UiEvent.ShowSnackbar(
                                "Compression note: Using original clip (${compError.localizedMessage ?: "fallback"})"
                            )
                        )
                        uploadFile = video.localFile
                    }
                )
            }

            // Step 2: Upload to Colab backend & Stream stylized MP4
            _uiState.update {
                it.copy(
                    processingState = ProcessingState.InProgress(
                        stage = StylizeProgress.Uploading(0, 0, uploadFile.length()),
                        stageDescription = "Uploading clip... 0%",
                        progressPercent = 0.25f
                    ),
                    isGallerySaved = false
                )
            }

            val result = apiService.stylizeVideo(
                context = getApplication(),
                baseUrl = serverUrl,
                videoFile = uploadFile,
                onProgress = { progress ->
                    val (desc, pct) = when (progress) {
                        is StylizeProgress.Compressing -> {
                            Pair("Compressing video... ${progress.progressPercent}%", (progress.progressPercent / 100f) * 0.2f)
                        }
                        is StylizeProgress.Uploading -> {
                            val percent = progress.progressPercent
                            Pair("Uploading clip... $percent%", 0.2f + (percent / 100f * 0.3f))
                        }
                        is StylizeProgress.RenderingOnGpu -> {
                            Pair("Colab GPU rendering anime frames...", 0.65f)
                        }
                        is StylizeProgress.Downloading -> {
                            val percent = if (progress.progressPercent >= 0) "${progress.progressPercent}%" else "streaming"
                            val factor = if (progress.progressPercent >= 0) progress.progressPercent / 100f * 0.35f else 0.2f
                            Pair("Downloading result... $percent", 0.65f + factor)
                        }
                        is StylizeProgress.Completed -> {
                            Pair("Stylization complete!", 1.0f)
                        }
                    }

                    _uiState.update {
                        it.copy(
                            processingState = ProcessingState.InProgress(
                                stage = progress,
                                stageDescription = desc,
                                progressPercent = pct
                            )
                        )
                    }
                }
            )

            result.fold(
                onSuccess = { generatedFile ->
                    try {
                        val clip = StylizedClip(
                            sourceFileName = video.fileName,
                            localFilePath = generatedFile.absolutePath,
                            createdAt = System.currentTimeMillis(),
                            durationMs = video.durationMs,
                            fileSizeBytes = generatedFile.length(),
                            serverUrl = serverUrl
                        )
                        val insertedId = repository.insertClip(clip)
                        val savedClip = clip.copy(id = insertedId)

                        _uiState.update {
                            it.copy(
                                processingState = ProcessingState.Success(savedClip),
                                activeResultClip = savedClip,
                                isGallerySaved = false
                            )
                        }
                        _uiEvents.emit(
                            UiEvent.ShowSnackbar("Anime stylization finished successfully! Ready to play.")
                        )
                    } catch (e: Exception) {
                        _uiEvents.emit(
                            UiEvent.ShowSnackbar("Clip created but failed to index in Room DB: ${e.localizedMessage}", isError = true)
                        )
                    }
                },
                onFailure = { error ->
                    val errorMsg = error.localizedMessage ?: "Stylization failed"
                    _uiState.update {
                        it.copy(processingState = ProcessingState.Failed(errorMsg))
                    }
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar(
                            message = errorMsg,
                            isError = true
                        )
                    )
                }
            )
        }
    }

    fun cancelStylization() {
        videoCompressor.cancel()
        apiService.cancelActiveRequest()
        activeStylizeJob?.cancel()
        _uiState.update {
            it.copy(processingState = ProcessingState.Idle)
        }
        viewModelScope.launch {
            _uiEvents.emit(UiEvent.ShowSnackbar("Stylization canceled."))
        }
    }

    fun saveActiveClipToGallery() {
        val clip = _uiState.value.activeResultClip ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isSavingToGallery = true) }
            val file = File(clip.localFilePath)
            val result = VideoUtils.saveVideoToGallery(getApplication(), file)
            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSavingToGallery = false,
                            isGallerySaved = true
                        )
                    }
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar("Saved to Gallery! You can find it in Movies/TitanAnime")
                    )
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isSavingToGallery = false) }
                    val message = error.localizedMessage ?: "Failed to save video to Gallery"
                    _uiEvents.emit(
                        UiEvent.ShowSnackbar("Gallery error: $message", isError = true)
                    )
                }
            )
        }
    }

    fun setActiveResultClip(clip: StylizedClip) {
        _uiState.update {
            it.copy(
                activeResultClip = clip,
                isGallerySaved = false
            )
        }
    }

    fun deleteCachedClip(clip: StylizedClip) {
        viewModelScope.launch {
            try {
                repository.deleteClip(clip)
                if (_uiState.value.activeResultClip?.id == clip.id) {
                    _uiState.update { it.copy(activeResultClip = null) }
                }
                _uiEvents.emit(UiEvent.ShowSnackbar("Clip deleted from offline cache."))
            } catch (e: Exception) {
                _uiEvents.emit(
                    UiEvent.ShowSnackbar("Could not delete clip: ${e.localizedMessage}", isError = true)
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        activeStylizeJob?.cancel()
        videoCompressor.cancel()
        apiService.cancelActiveRequest()
    }
}
