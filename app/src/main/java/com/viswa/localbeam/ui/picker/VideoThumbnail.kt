package com.viswa.localbeam.ui.picker

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.viswa.localbeam.data.model.LocalVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a video's poster frame off the main thread.
 *
 * Android 10+ has [android.content.ContentResolver.loadThumbnail]; older releases
 * fall back to the (deprecated) thumbnails table. Results are cached in memory so
 * scrolling the list does not re-decode frames.
 */
@Composable
fun rememberVideoThumbnail(video: LocalVideo): ImageBitmap? {
    val context = LocalContext.current
    var thumbnail by remember(video.id) { mutableStateOf(ThumbnailCache.get(video.id)) }

    LaunchedEffect(video.id) {
        if (thumbnail == null) {
            thumbnail = withContext(Dispatchers.IO) { loadThumbnail(context, video) }
                ?.also { ThumbnailCache.put(video.id, it) }
        }
    }
    return thumbnail
}

private object ThumbnailCache {
    private val cache = LruCache<Long, ImageBitmap>(64)

    fun get(videoId: Long): ImageBitmap? = cache.get(videoId)

    fun put(videoId: Long, bitmap: ImageBitmap) {
        cache.put(videoId, bitmap)
    }
}

private const val THUMBNAIL_WIDTH = 320
private const val THUMBNAIL_HEIGHT = 180

private fun loadThumbnail(context: Context, video: LocalVideo): ImageBitmap? = runCatching {
    val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.contentResolver.loadThumbnail(
            Uri.parse(video.uri),
            Size(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT),
            null,
        )
    } else {
        @Suppress("DEPRECATION")
        MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver,
            video.id,
            MediaStore.Video.Thumbnails.MINI_KIND,
            null,
        )
    }
    bitmap?.asImageBitmap()
}.getOrNull()
