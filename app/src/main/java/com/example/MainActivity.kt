package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.ProcessingState
import com.example.ui.ServerConnectionState
import com.example.ui.TitanAnimeViewModel
import com.example.ui.UiEvent
import com.example.ui.components.CachedClipsSection
import com.example.ui.components.ColabHelpDialog
import com.example.ui.components.ResultPlayerCard
import com.example.ui.components.ServerConfigCard
import com.example.ui.components.StylizeProgressSection
import com.example.ui.components.VideoPickerCard
import com.example.ui.theme.AnimeCrimson
import com.example.ui.theme.DarkCharcoal
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DimText
import com.example.ui.theme.LightText
import com.example.ui.theme.MediumText
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusGrey
import com.example.ui.theme.TitanAnimeTheme
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val viewModel: TitanAnimeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TitanAnimeTheme {
                TitanAnimeScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitanAnimeScreen(
    viewModel: TitanAnimeViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cachedClips by viewModel.cachedClips.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showHelpDialog by remember { mutableStateOf(false) }

    // Unified UI event listener for Snackbars and Toasts
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collectLatest { event ->
            when (event) {
                is UiEvent.ShowSnackbar -> {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        actionLabel = event.actionLabel,
                        duration = if (event.isError) SnackbarDuration.Long else SnackbarDuration.Short
                    )
                }
                is UiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val isServerConnected = uiState.connectionState is ServerConnectionState.Connected
    val isVideoSelected = uiState.selectedVideo != null

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = DarkCharcoal,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { snackbarData ->
                Snackbar(
                    snackbarData = snackbarData,
                    containerColor = DarkSurface,
                    contentColor = LightText,
                    actionColor = AnimeCrimson,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        // Brand emblem badge
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AnimeCrimson),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "T",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = "Titan Anime",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                color = LightText,
                                letterSpacing = 0.5.sp
                            )
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        // Status dot indicator updated in real-time by periodic background polling
                        TopBarStatusIndicator(isOnline = isServerConnected)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showHelpDialog = true },
                        modifier = Modifier.testTag("help_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.HelpOutline,
                            contentDescription = "Help & Colab API Info",
                            tint = MediumText
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = LightText
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Server URL Card (with configurable background health polling)
            item {
                ServerConfigCard(
                    serverUrl = uiState.serverUrl,
                    connectionState = uiState.connectionState,
                    pollingInterval = uiState.pollingInterval,
                    onUrlChange = { viewModel.onServerUrlChange(it) },
                    onConnectClick = { viewModel.checkConnection() },
                    onPollingIntervalChange = { viewModel.setPollingInterval(it) }
                )
            }

            // 2. Video Picker Card (with client-side video compression options)
            item {
                VideoPickerCard(
                    selectedVideo = uiState.selectedVideo,
                    isResolving = uiState.isResolvingVideo,
                    compressionQuality = uiState.compressionQuality,
                    onVideoSelected = { viewModel.onVideoSelected(it) },
                    onClearVideo = { viewModel.clearSelectedVideo() },
                    onCompressionQualityChange = { viewModel.setCompressionQuality(it) }
                )
            }

            // 3. Convert CTA & Progress Section (showing compression, upload, GPU, and download)
            item {
                StylizeProgressSection(
                    isServerOnline = isServerConnected,
                    isVideoSelected = isVideoSelected,
                    processingState = uiState.processingState,
                    onStylizeClick = { viewModel.stylizeVideo() },
                    onCancelClick = { viewModel.cancelStylization() }
                )
            }

            // 4. Result Card (Media3 ExoPlayer + Save to Gallery button)
            if (uiState.activeResultClip != null) {
                item {
                    ResultPlayerCard(
                        clip = uiState.activeResultClip!!,
                        isSaving = uiState.isSavingToGallery,
                        isSaved = uiState.isGallerySaved,
                        onSaveToGalleryClick = { viewModel.saveActiveClipToGallery() }
                    )
                }
            }

            // 5. Offline Library (Room DB Cache)
            item {
                CachedClipsSection(
                    clips = cachedClips,
                    activeClipId = uiState.activeResultClip?.id,
                    onClipSelect = { viewModel.setActiveResultClip(it) },
                    onClipDelete = { viewModel.deleteCachedClip(it) }
                )
            }

            // Bottom padding for comfortable scrolling
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showHelpDialog) {
        ColabHelpDialog(onDismiss = { showHelpDialog = false })
    }
}

/**
 * Top App Bar Status Dot:
 * Green = Connected, Grey = Not Connected
 */
@Composable
fun TopBarStatusIndicator(
    isOnline: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = if (isOnline) StatusGreen else StatusGrey
    val label = if (isOnline) "Connected" else "Not Connected"

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurfaceContainer)
            .border(1.dp, OutlineDark, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = if (isOnline) StatusGreen else DimText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
