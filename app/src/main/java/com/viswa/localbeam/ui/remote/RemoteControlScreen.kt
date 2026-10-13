package com.viswa.localbeam.ui.remote

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa.localbeam.core.MediaPermissions
import com.viswa.localbeam.core.TimeUtils
import com.viswa.localbeam.ui.components.Banner
import com.viswa.localbeam.ui.components.BannerTone
import com.viswa.localbeam.ui.components.LocalBeamScaffold
import com.viswa.localbeam.ui.components.PlayPauseGlyph
import com.viswa.localbeam.ui.components.SectionLabel

/**
 * Screen 3: the remote.
 *
 * Play/pause, ±10 second seeking and a progress slider, plus the address the stream is
 * served on (handy when a TV refuses to load it).
 */
@Composable
fun RemoteControlScreen(
    onBack: () -> Unit,
    onSessionEnded: () -> Unit,
    viewModel: RemoteControlViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Starting the foreground service without this permission still works, but the
    // user would lose the "streaming" notification and its Stop action.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { viewModel.onScreenEntered() }

    LaunchedEffect(Unit) {
        val needsNotifications = !MediaPermissions.hasNotificationAccess(context) &&
            MediaPermissions.notifications.isNotEmpty()
        if (needsNotifications) {
            notificationLauncher.launch(MediaPermissions.notifications.first())
        } else {
            viewModel.onScreenEntered()
        }
    }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    LocalBeamScaffold(
        title = state.videoTitle ?: "Remote",
        subtitle = state.deviceName?.let { "on $it" },
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            StatusCard(
                status = state.statusLabel,
                serverUrl = state.serverUrl,
                isPlaying = state.isPlaying,
                rendererReachable = state.rendererReachable,
            )

            state.error?.let { message ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Banner(message, BannerTone.Error)
                    TextButton(onClick = viewModel::dismissError) { Text("Dismiss") }
                }
            }

            ProgressSection(
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                enabled = state.isCasting && state.durationSeconds > 0L,
                onSeek = viewModel::seekTo,
            )

            TransportRow(
                isPlaying = state.isPlaying,
                enabled = state.isCasting && !state.isBusy,
                onRewind = { viewModel.seekBy(-SEEK_STEP_SECONDS) },
                onTogglePlay = viewModel::togglePlayPause,
                onForward = { viewModel.seekBy(SEEK_STEP_SECONDS) },
            )


            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = viewModel::stopPlayback,
                    modifier = Modifier.weight(1f),
                    enabled = state.isCasting && !state.isBusy,
                ) {
                    Text("Stop")
                }
                Button(
                    onClick = { viewModel.endSession(onSessionEnded) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("End session")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val SEEK_STEP_SECONDS = 10L

@Composable
private fun StatusCard(
    status: String,
    serverUrl: String?,
    isPlaying: Boolean,
    rendererReachable: Boolean,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        !rendererReachable -> MaterialTheme.colorScheme.error
                        isPlaying -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(10.dp),
                ) {}
                Text(
                    text = status,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            SectionLabel("Served from")
            Text(
                text = serverUrl ?: "not started yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressSection(
    positionSeconds: Long,
    durationSeconds: Long,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
) {
    // While the finger is down the slider follows the finger, not the poll.
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val range = durationSeconds.coerceAtLeast(1L).toFloat()
    val sliderValue = if (isDragging) {
        dragValue
    } else {
        positionSeconds.toFloat().coerceIn(0f, range)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                isDragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragValue.toLong())
            },
            valueRange = 0f..range,
            enabled = enabled,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = TimeUtils.formatClock(if (isDragging) dragValue.toLong() else positionSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = TimeUtils.formatClock(durationSeconds),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    enabled: Boolean,
    onRewind: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeekButton(text = "−10s", enabled = enabled, onClick = onRewind)

        Spacer(Modifier.size(24.dp))

        Button(
            onClick = onTogglePlay,
            enabled = enabled,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                PlayPauseGlyph(
                    playing = isPlaying,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.size(24.dp))

        SeekButton(text = "+10s", enabled = enabled, onClick = onForward)
    }
}

@Composable
private fun SeekButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}
