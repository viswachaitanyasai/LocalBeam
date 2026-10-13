package com.viswa.localbeam.ui.picker

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.viswa.localbeam.core.TimeUtils
import com.viswa.localbeam.data.model.LocalVideo
import com.viswa.localbeam.ui.components.Banner
import com.viswa.localbeam.ui.components.BannerTone
import com.viswa.localbeam.ui.components.BusyRow
import com.viswa.localbeam.ui.components.EmptyState
import com.viswa.localbeam.ui.components.LocalBeamScaffold
import com.viswa.localbeam.ui.components.SectionLabel

/**
 * The video step of the picker: everything inside the folder that was chosen on the
 * previous screen (or the whole library, for "All videos").
 */
@Composable
fun VideoPickerScreen(
    deviceName: String?,
    onVideoSelected: () -> Unit,
    onBack: () -> Unit,
    viewModel: VideoPickerViewModel = viewModel(),
) {
    val requestPermission = MediaLibraryGate(viewModel)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LocalBeamScaffold(
        title = state.folderLabel,
        subtitle = deviceName?.let { "Casting to $it" },
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isLoading) {
                BusyRow("Reading your video library…")
            }

            state.error?.let { message ->
                Banner(message, BannerTone.Error)
                if (state.needsPermission) {
                    OutlinedButton(onClick = requestPermission) { Text("Grant access") }
                }
            }

            if (state.isEmpty) {
                EmptyState(
                    title = "No videos on this device",
                    message = "LocalBeam streams videos from shared storage. Copy a video to the " +
                        "device (or insert an SD card) and try again.",
                )
            } else if (state.visibleVideos.isNotEmpty()) {
                SectionLabel("${state.folderLocation} · ${state.visibleVideos.size} video(s)")
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.visibleVideos, key = { it.id }) { video ->
                    VideoRow(
                        video = video,
                        onClick = {
                            viewModel.selectVideo(video)
                            onVideoSelected()
                        },
                    )
                }
                item { Column(Modifier.padding(bottom = 16.dp)) {} }
            }
        }
    }
}

@Composable
private fun VideoRow(video: LocalVideo, onClick: () -> Unit) {
    val thumbnail = rememberVideoThumbnail(video)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = video.extension.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // The file's own name: long names get the width the Cast button used to
                    // take and wrap over three lines instead of collapsing to one word.
                    text = video.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(TimeUtils.formatDuration(video.durationMs))
                        append(" · ")
                        append(TimeUtils.formatSize(video.sizeBytes))
                        append(" · ")
                        append(video.extension.uppercase())
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Same affordance as the folder list: the card itself is the target.
            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
