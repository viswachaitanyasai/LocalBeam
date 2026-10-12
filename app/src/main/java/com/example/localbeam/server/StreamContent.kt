package com.example.localbeam.server

import com.example.localbeam.data.model.LocalVideo
import com.example.localbeam.data.model.VideoFormats

/**
 * The file the local server exposes, described in the terms the HTTP layer and the
 * UPnP metadata both need.
 *
 * The URL always ends in a real extension (`/video.mkv`), because several renderers
 * sniff the path before they look at `Content-Type`.
 *
 * [startSeconds] turns the same file into a *window*: the server then behaves as if
 * the file began at that position. That is how a seek works on a renderer which
 * refuses UPnP `Seek` (many do, for Matroska): the app repoints the TV at a stream
 * that starts at the requested timestamp, so the TV never needs to seek at all.
 */
data class StreamContent(
    /** Content URI of the file, as a string so this model stays platform independent. */
    val uri: String,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val extension: String,
    /** Seconds into the file where this stream starts; `0` for a normal cast. */
    val startSeconds: Long = 0L,
    /**
     * Short random token that makes this stream's URL unique.
     *
     * Renderers key their item state on the URL. Samsung TVs in particular answer
     * "this file format is not supported" when handed a URL they have already played
     * and stopped – which is exactly what a repositioned stream used to look like.
     */
    val token: String = "",
) {
    /** `video.mkv`, or `video-a1b2c3d4.mkv` when the stream carries a token. */
    val fileName: String
        get() = if (token.isBlank()) "video.$extension" else "video-$token.$extension"

    /** Path the TV will request. */
    val path: String get() = "/$fileName"

    /** `video/mp4` – never `application/octet-stream`, which some TVs refuse. */
    val contentType: String get() = VideoFormats.normalizeMime(mimeType, fileName)

    /** Seconds, for the DIDL-Lite `duration` attribute; `0` when unknown. */
    val durationSeconds: Long get() = if (durationMs > 0L) durationMs / 1000 else 0L

    /** True when this stream starts part-way into the file. */
    val isWindowed: Boolean get() = startSeconds > 0L && durationMs > 0L

    /**
     * Slice of the file this stream exposes, measured against [sizeBytes].
     *
     * This is where the resume position turns into a byte offset; the server derives the
     * same window again from the file's real length (the two agree in practice), and the
     * result also feeds the DIDL-Lite metadata the TV is given up front.
     */
    fun window(): StreamWindow = StreamWindow.of(this, sizeBytes)

    companion object {
        /**
         * @param startSeconds where playback should resume, in seconds from the start.
         * @param durationMs the best known duration: MediaStore's value when it has
         *   one, otherwise what the renderer reported. Seeking needs it, and some
         *   containers (Matroska in particular) are missing it in MediaStore.
         */
        fun from(
            video: LocalVideo,
            startSeconds: Long = 0L,
            durationMs: Long = video.durationMs,
            token: String = "",
        ): StreamContent = StreamContent(
            uri = video.uri,
            title = video.title,
            mimeType = video.mimeType,
            sizeBytes = video.sizeBytes,
            durationMs = durationMs,
            extension = video.extension,
            startSeconds = startSeconds.coerceAtLeast(0L),
            token = token,
        )
    }
}
