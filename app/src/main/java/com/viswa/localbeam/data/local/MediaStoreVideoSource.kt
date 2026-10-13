package com.viswa.localbeam.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.viswa.localbeam.data.model.LocalVideo
import com.viswa.localbeam.data.model.VideoFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's video library through [MediaStore], honouring scoped storage.
 *
 * Only metadata is read here; the actual bytes stay on disk and are streamed
 * through the [com.viswa.localbeam.server.VideoHttpServer] on demand.
 *
 * Each row also carries the directory it lives in, which is what the folder-first
 * picker groups by.
 */
class MediaStoreVideoSource(private val context: Context) {

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * All indexed videos, newest first.
     *
     * Throws [SecurityException] when the media permission has not been granted,
     * which the UI turns into a "grant permission" prompt.
     */
    suspend fun queryVideos(): List<LocalVideo> = withContext(Dispatchers.IO) {
        val collection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val videos = mutableListOf<LocalVideo>()

        resolver.query(
            collection,
            projection(),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val columns = Columns(cursor)

            while (cursor.moveToNext()) {
                readVideo(cursor, collection, columns)?.let(videos::add)
            }
        }
        videos
    }

    /**
     * `RELATIVE_PATH` (and scoped-storage folder columns) only exist from Android 10;
     * asking for them on older releases makes the query itself fail.
     */
    private fun projection(): Array<String> = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.TITLE)
        add(MediaStore.Video.Media.MIME_TYPE)
        add(MediaStore.Video.Media.SIZE)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Video.Media.DATA)
        add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(MediaStore.Video.Media.RELATIVE_PATH)
        }
    }.toTypedArray()

    private class Columns(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val displayName = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
        val title = cursor.getColumnIndex(MediaStore.Video.Media.TITLE)
        val mimeType = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
        val size = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
        val duration = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
        val data = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
        val bucket = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
        } else {
            -1
        }
    }

    private fun readVideo(cursor: Cursor, collection: Uri, columns: Columns): LocalVideo? {
        val id = cursor.getLong(columns.id)
        val displayName = cursor.string(columns.displayName).orEmpty()
        val title = cursor.string(columns.title)
            ?.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.')
        val mime = cursor.string(columns.mimeType).orEmpty()
        val size = cursor.long(columns.size) ?: 0L
        val duration = cursor.long(columns.duration) ?: 0L

        if (title.isBlank()) return null
        if (mime.isNotEmpty() && !mime.startsWith("video/")) return null
        // Skip empty placeholders; MediaStore keeps rows for files that are still being written.
        if (size <= 0L) return null

        val bucketName = cursor.string(columns.bucket)
        val folderPath = VideoFolders.folderPath(
            dataPath = cursor.string(columns.data),
            relativePath = cursor.string(columns.relativePath),
            bucketName = bucketName,
        )

        return LocalVideo(
            id = id,
            uri = ContentUris.withAppendedId(collection, id).toString(),
            title = title,
            mimeType = mime,
            sizeBytes = size,
            durationMs = duration,
            folderPath = folderPath,
            folderName = VideoFolders.folderName(folderPath, bucketName),
            fileName = displayName,
        )
    }

    private fun Cursor.string(columnIndex: Int): String? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getString(columnIndex) else null

    private fun Cursor.long(columnIndex: Int): Long? =
        if (columnIndex >= 0 && !isNull(columnIndex)) getLong(columnIndex) else null
}
