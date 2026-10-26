package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.network.StylizeProgress
import com.example.ui.ProcessingState
import com.example.ui.theme.AnimeCrimson
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DimText
import com.example.ui.theme.LightText
import com.example.ui.theme.MediumText
import com.example.ui.theme.OutlineDark

@Composable
fun StylizeProgressSection(
    isServerOnline: Boolean,
    isVideoSelected: Boolean,
    processingState: ProcessingState,
    onStylizeClick: () -> Unit,
    onCancelClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isProcessing = processingState is ProcessingState.InProgress
    val isButtonEnabled = isServerOnline && isVideoSelected && !isProcessing

    Column(modifier = modifier.fillMaxWidth()) {
        // Convert CTA Button
        Button(
            onClick = onStylizeClick,
            enabled = isButtonEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("stylize_video_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AnimeCrimson,
                contentColor = Color.White,
                disabledContainerColor = if (isProcessing) AnimeCrimson.copy(alpha = 0.5f) else DarkSurfaceContainer,
                disabledContentColor = DimText
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isButtonEnabled) Color.White else DimText
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isProcessing) "Stylizing in Progress..." else "Stylize Video to Anime",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                )
            }
        }

        // Explanatory hint if button disabled
        if (!isButtonEnabled && !isProcessing) {
            Spacer(modifier = Modifier.height(6.dp))
            val reason = when {
                !isServerOnline && !isVideoSelected -> "Connect to Colab server and choose a video above to begin"
                !isServerOnline -> "Colab GPU server must be connected first"
                !isVideoSelected -> "Select a source video clip above to continue"
                else -> ""
            }
            if (reason.isNotEmpty()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = DimText,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }

        // Progress Section Card
        AnimatedVisibility(
            visible = isProcessing,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            if (processingState is ProcessingState.InProgress) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag("progress_section_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = androidx.compose.ui.graphics.SolidColor(AnimeCrimson.copy(alpha = 0.4f))
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val stageIcon = when (processingState.stage) {
                                    is StylizeProgress.Compressing -> Icons.Default.Compress
                                    is StylizeProgress.Uploading -> Icons.Default.CloudUpload
                                    is StylizeProgress.RenderingOnGpu -> Icons.Default.Memory
                                    is StylizeProgress.Downloading -> Icons.Default.Download
                                    is StylizeProgress.Completed -> Icons.Default.AutoAwesome
                                }

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(AnimeCrimson.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = stageIcon,
                                        contentDescription = null,
                                        tint = AnimeCrimson,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = processingState.stageDescription,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = LightText
                                        )
                                    )
                                    Text(
                                        text = when (processingState.stage) {
                                            is StylizeProgress.Compressing -> "Transcoding video for fast GPU upload..."
                                            is StylizeProgress.Uploading -> "Streaming clip to remote GPU..."
                                            is StylizeProgress.RenderingOnGpu -> "Running PyTorch neural anime diffusion..."
                                            is StylizeProgress.Downloading -> "Receiving stylized MP4 stream..."
                                            is StylizeProgress.Completed -> "Done!"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = DimText,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onCancelClick,
                                shape = RoundedCornerShape(8.dp),
                                border = CardDefaults.outlinedCardBorder().copy(
                                    brush = androidx.compose.ui.graphics.SolidColor(OutlineDark)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = "Cancel",
                                    tint = DimText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Cancel",
                                    color = DimText,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        if (processingState.progressPercent != null && processingState.progressPercent >= 0f) {
                            LinearProgressIndicator(
                                progress = { processingState.progressPercent },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AnimeCrimson,
                                trackColor = DarkSurfaceContainer
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = AnimeCrimson,
                                trackColor = DarkSurfaceContainer
                            )
                        }
                    }
                }
            }
        }

        // Error display
        if (processingState is ProcessingState.Failed) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.error)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Stylization Error",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = LightText
                            )
                        )
                        Text(
                            text = processingState.error,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MediumText,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
