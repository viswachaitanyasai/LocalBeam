package com.example.localbeam.server

/**
 * The slice of the file the server exposes to the TV, in both byte and time terms.
 *
 * For a normal cast this is the whole file: `offsetBytes = 0`, `length = totalBytes`.
 * For a *repositioned* stream it is "the file from [offsetBytes] on", and the bytes
 * the TV asks for are always relative to that window – which is what lets a TV that
 * refuses UPnP `Seek` still jump to a timestamp: it just plays a new URL that starts
 * there.
 *
 * The time side matters because a byte offset is all we can produce: the files are
 * never indexed, so [offsetBytes] is proportional to [startSeconds] at an even
 * bitrate. That is approximate for variable-bitrate files, which is exactly why the
 * app prefers a real UPnP `Seek` and only falls back to a reposition when the TV
 * will not seek at all.
 */
data class StreamWindow(
    /** Real file byte the TV's byte 0 maps to. */
    val offsetBytes: Long,
    /** Bytes the TV can ask for: `totalBytes - offsetBytes`. */
    val length: Long,
    /** Size of the whole file. */
    val totalBytes: Long,
    /** Seconds into the file this window starts at. */
    val startSeconds: Long,
    /** Duration of the whole file, `0` when unknown. */
    val fullDurationMs: Long,
) {

    /** True when this stream starts part-way into the file. */
    val isWindowed: Boolean get() = offsetBytes > 0L

    /** Playtime left from [startSeconds] on; what the TV will be told to expect. */
    val remainingDurationMs: Long
        get() = if (isWindowed && fullDurationMs > 0L) {
            (fullDurationMs - startSeconds * 1000L).coerceAtLeast(1_000L)
        } else {
            fullDurationMs
        }

    /** [remainingDurationMs] in seconds. */
    val remainingDurationSeconds: Long
        get() = if (remainingDurationMs > 0L) remainingDurationMs / 1000 else 0L

    /** Where in the real file a byte the TV asked for lives. */
    fun realOffset(virtualOffset: Long): Long = offsetBytes + virtualOffset

    /** Playtime of a position the TV reports, measured from the start of the file. */
    fun absoluteSeconds(virtualSeconds: Long): Long = startSeconds + virtualSeconds

    companion object {

        /** Window described by [content], measured against the file's real size. */
        fun of(content: StreamContent, totalBytes: Long): StreamWindow {
            if (totalBytes <= 0L) return StreamWindow(0L, 0L, 0L, content.startSeconds, content.durationMs)
            val offset = if (content.isWindowed) {
                DlnaTimeSeek
                    .toByteOffset(content.startSeconds, content.durationMs, totalBytes)
                    // Keep at least one byte in the window, even for a seek to the end.
                    .coerceIn(0L, totalBytes - 1L)
            } else {
                0L
            }
            return StreamWindow(
                offsetBytes = offset,
                length = totalBytes - offset,
                totalBytes = totalBytes,
                startSeconds = if (offset > 0L) content.startSeconds else 0L,
                fullDurationMs = content.durationMs,
            )
        }
    }
}
