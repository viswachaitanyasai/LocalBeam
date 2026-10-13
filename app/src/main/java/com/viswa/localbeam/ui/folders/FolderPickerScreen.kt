package com.viswa.localbeam.ui.folders

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
import com.viswa.localbeam.data.model.VideoFolder
import com.viswa.localbeam.ui.components.Banner
import com.viswa.localbeam.ui.components.BannerTone
import com.viswa.localbeam.ui.components.BusyRow
import com.viswa.localbeam.ui.components.EmptyState
import com.viswa.localbeam.ui.components.LocalBeamScaffold
import com.viswa.localbeam.ui.components.SectionLabel
import com.viswa.localbeam.ui.picker.MediaLibraryGate
import com.viswa.localbeam.ui.picker.VideoPickerViewModel
import com.viswa.localbeam.ui.picker.rememberVideoThumbnail

/**
 * The folder step of the picker: choose where the video lives, then pick the video.
 *
 * Folders are derived from the videos themselves, so only directories that actually
 * contain playable files are listed. "All videos" is offered first for anyone who
 * would rather browse the whole library at once.
 */
@Composable
fun FolderPickerScreen(
    deviceName: String?,
    onFolderSelected: () -> Unit,
    onBack: () -> Unit,
    viewModel: VideoPickerViewModel = viewModel(),
) {
    val requestPermission = MediaLibraryGate(viewModel)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LocalBeamScaffold(
        title = "Choose a folder",
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
            }

            if (state.folders.isNotEmpty()) {
                SectionLabel(
                    "${state.folders.size - 1} folder(s) · ${state.videos.size} video(s)",
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.folders, key = { it.path }) { folder ->
                    FolderRow(
                        folder = folder,
                        onClick = {
                            viewModel.selectFolder(folder.path)
                            onFolderSelected()
                        },
                    )
                }
                item { Column(Modifier.padding(bottom = 16.dp)) {} }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: VideoFolder, onClick: () -> Unit) {
    val preview = folder.preview
    val thumbnail = if (preview != null) rememberVideoThumbnail(preview) else null

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
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (folder.isAllVideos || thumbnail == null) {
                            // No poster frame available: show the size of the folder instead.
                            Text(
                                text = if (folder.isAllVideos) "ALL" else "${folder.videoCount}",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Image(
                                bitmap = thumbnail,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.breadcrumb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${folder.videoCount} video(s) · ${TimeUtils.formatSize(folder.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "›",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
