package com.example.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.PollingInterval
import com.example.ui.ServerConnectionState
import com.example.ui.theme.AnimeCrimson
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceContainer
import com.example.ui.theme.DimText
import com.example.ui.theme.LightText
import com.example.ui.theme.OutlineDark
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusGrey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ServerConfigCard(
    serverUrl: String,
    connectionState: ServerConnectionState,
    pollingInterval: PollingInterval,
    onUrlChange: (String) -> Unit,
    onConnectClick: () -> Unit,
    onPollingIntervalChange: (PollingInterval) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var showPollingSettings by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_url_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkSurface
        ),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(OutlineDark)
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
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = "Server",
                        tint = AnimeCrimson,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Colab GPU Backend",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = LightText
                        )
                    )
                }

                // Connection status pill
                ConnectionStatusPill(connectionState = connectionState)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = serverUrl,
                onValueChange = onUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_url_input"),
                placeholder = {
                    Text(
                        text = "https://xxxx.trycloudflare.com",
                        color = DimText,
                        fontSize = 14.sp
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        onConnectClick()
                    }
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (serverUrl.isNotEmpty()) {
                            IconButton(onClick = { onUrlChange("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear URL",
                                    tint = DimText,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    onUrlChange(clip.trim())
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste from clipboard",
                                tint = AnimeCrimson,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AnimeCrimson,
                    unfocusedBorderColor = OutlineDark,
                    focusedTextColor = LightText,
                    unfocusedTextColor = LightText,
                    cursorColor = AnimeCrimson,
                    focusedContainerColor = DarkSurfaceContainer,
                    unfocusedContainerColor = DarkSurfaceContainer
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Background polling toggle / status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = { showPollingSettings = !showPollingSettings },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Background polling settings",
                            tint = if (pollingInterval != PollingInterval.OFF) AnimeCrimson else DimText,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (pollingInterval == PollingInterval.OFF) "Poll: Off" else "Poll: every ${pollingInterval.minutes}m",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = DimText,
                            fontSize = 11.sp
                        )
                    )
                }

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onConnectClick()
                    },
                    modifier = Modifier.testTag("connect_button"),
                    enabled = connectionState !is ServerConnectionState.Checking,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AnimeCrimson,
                        contentColor = Color.White,
                        disabledContainerColor = AnimeCrimson.copy(alpha = 0.4f)
                    )
                ) {
                    if (connectionState is ServerConnectionState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Pinging...", fontSize = 13.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connectionState is ServerConnectionState.Connected) "Re-check" else "Connect",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Polling interval selector collapsible chips
            AnimatedVisibility(visible = showPollingSettings) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceContainer)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Background Health Polling (GET /):",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = LightText,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PollingInterval.values().forEach { interval ->
                            val isSelected = interval == pollingInterval
                            FilterChip(
                                selected = isSelected,
                                onClick = { onPollingIntervalChange(interval) },
                                label = {
                                    Text(
                                        text = interval.label.replace(" (Default)", ""),
                                        fontSize = 11.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AnimeCrimson,
                                    selectedLabelColor = Color.White,
                                    containerColor = DarkSurface,
                                    labelColor = DimText
                                )
                            )
                        }
                    }
                }
            }

            if (connectionState is ServerConnectionState.Error) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = connectionState.message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}

@Composable
fun ConnectionStatusPill(
    connectionState: ServerConnectionState,
    modifier: Modifier = Modifier
) {
    val (dotColor, text, subtext) = when (connectionState) {
        is ServerConnectionState.Connected -> {
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(connectionState.lastCheckedAt))
            Triple(
                StatusGreen,
                "Online",
                "${connectionState.latencyMs}ms • $timeStr"
            )
        }
        is ServerConnectionState.Checking -> Triple(
            AnimeCrimson,
            "Checking...",
            ""
        )
        is ServerConnectionState.Error -> Triple(
            MaterialTheme.colorScheme.error,
            "Offline",
            ""
        )
        is ServerConnectionState.Disconnected -> Triple(
            StatusGrey,
            "Not Connected",
            ""
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceContainer)
            .border(1.dp, OutlineDark, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                color = LightText,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp
            )
        )
        if (subtext.isNotEmpty()) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "($subtext)",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = DimText,
                    fontSize = 10.sp
                )
            )
        }
    }
}
