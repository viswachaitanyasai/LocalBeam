package com.example.localbeam.server

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Random access to the bytes of the file being streamed.
 *
 * Kept behind an interface so [VideoHttpServer] can be exercised over a real socket
 * in JVM tests (where there is no `ContentResolver`), while production code reads
 * through MediaStore.
 */
interface VideoByteSource {

    /** Total size in bytes, or `-1` when it cannot be determined. */
    val length: Long

    /**
     * Opens a stream positioned at [start] and limited to [count] bytes.
     *
     * Every call must return an independent stream: a renderer routinely starts the
     * next range before dropping the previous connection, and two streams sharing one
     * file position would corrupt each other's reads.
     */
    @Throws(IOException::class)
    fun open(start: Long, count: Long): InputStream
}

/**
 * Reads the selected video through the content resolver, honouring scoped storage.
 *
 * `openFileDescriptor` is preferred because it hands back a descriptor positioned at
 * byte 0 of the file; `openAssetFileDescriptor` is the fallback for providers that
 * only expose offset-based access.
 */
class ContentResolverByteSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val fallbackLength: Long,
) : VideoByteSource {

    private val resolvedLength: Long by lazy { resolveLength() }

    override val length: Long get() = resolvedLength

    override fun open(start: Long, count: Long): InputStream {
        val fileDescriptor: FileDescriptor
        val baseOffset: Long

        val plain = resolver.openFileDescriptor(uri, "r")
        if (plain != null) {
            // The stream below owns the descriptor; closing the ParcelFileDescriptor
            // as well would close the same fd twice.
            fileDescriptor = plain.fileDescriptor
            baseOffset = 0L
        } else {
            val asset = resolver.openAssetFileDescriptor(uri, "r")
                ?: throw IOException("Could not open $uri")
            fileDescriptor = asset.fileDescriptor
            baseOffset = asset.startOffset
        }

        val stream = FileInputStream(fileDescriptor)
        try {
            stream.channel.position(baseOffset + start)
        } catch (t: Throwable) {
            runCatching { stream.close() }
            throw IOException("Could not seek to offset $start in $uri", t)
        }
        return LimitedInputStream(stream, count)
    }

    private fun resolveLength(): Long {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it > 0L }?.let { return it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not stat $uri via openFileDescriptor", t)
        }

        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }?.let { return it }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not stat $uri via openAssetFileDescriptor", t)
        }

        return fallbackLength
    }

    private companion object {
        const val TAG = "VideoByteSource"
    }
}

/** Returns at most [limit] bytes from [source], then EOF; closing closes [source]. */
internal class LimitedInputStream(
    private val source: InputStream,
    private var remaining: Long,
) : FilterInputStream(source) {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = super.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        if (requested <= 0) return 0
        val read = super.read(buffer, offset, requested)
        if (read > 0) remaining -= read
        return read
    }

    override fun available(): Int = minOf(remaining, super.available().toLong()).toInt()
}
