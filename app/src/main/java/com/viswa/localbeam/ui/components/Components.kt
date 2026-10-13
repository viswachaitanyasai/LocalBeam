package com.viswa.localbeam.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for the three screens.
 *
 * The few glyphs the app needs (back chevron, play/pause) are drawn with [Canvas]
 * instead of pulling in an icon pack – this app deliberately stays dependency-light.
 */

@Composable
fun LocalBeamScaffold(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { LocalBeamHeader(title, subtitle, onBack, actions) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) { content(padding) }
    }
}

@Composable
private fun LocalBeamHeader(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    actions: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The Scaffold only pads the content for the system bars, so the header
                // claims the status bar inset itself (the app draws edge to edge).
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) { BackChevron() }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            actions()
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Left-pointing chevron, drawn rather than imported. */
@Composable
fun BackChevron(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier = modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        val midY = size.height / 2f
        drawLine(
            color = color,
            start = Offset(size.width * 0.72f, size.height * 0.2f),
            end = Offset(size.width * 0.3f, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.3f, midY),
            end = Offset(size.width * 0.72f, size.height * 0.8f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, midY),
            end = Offset(size.width * 0.92f, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** Triangle when paused, two bars when playing. */
@Composable
fun PlayPauseGlyph(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Canvas(modifier = modifier.size(28.dp)) {
        if (playing) {
            val barWidth = size.width * 0.24f
            val gap = size.width * 0.16f
            val top = size.height * 0.16f
            val barHeight = size.height * 0.68f
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width / 2 - gap / 2 - barWidth, top),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.25f),
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(size.width / 2 + gap / 2, top),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth * 0.25f),
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.24f, size.height * 0.14f)
                lineTo(size.width * 0.86f, size.height / 2f)
                lineTo(size.width * 0.24f, size.height * 0.86f)
                close()
            }
            drawPath(path, color)
        }
    }
}

enum class BannerTone { Info, Error, Success }

@Composable
fun Banner(text: String, tone: BannerTone, modifier: Modifier = Modifier) {
    val container = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.surfaceVariant
        BannerTone.Error -> MaterialTheme.colorScheme.errorContainer
        BannerTone.Success -> MaterialTheme.colorScheme.secondaryContainer
    }
    val onContainer = when (tone) {
        BannerTone.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        BannerTone.Error -> MaterialTheme.colorScheme.onErrorContainer
        BannerTone.Success -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = container,
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = onContainer,
        )
    }
}

@Composable
fun BusyRow(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Section label used above lists. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

