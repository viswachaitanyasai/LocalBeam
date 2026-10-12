package com.example.localbeam.server

import com.example.localbeam.core.TimeUtils

/**
 * DLNA's optional time-based seek:
 * ```
 * TimeSeekRange.dlna.org: npt=00:01:00.000-00:02:00.000/00:45:00.000
 * ```
 *
 * Our `res` metadata advertises `DLNA.ORG_OP=01` (byte seek), which is what most
 * renderers use, but a few only ever ask in `npt` (normal play time) form. This
 * converts the requested start time into a byte offset using the known duration.
 */
object DlnaTimeSeek {

    data class Request(
        val startSeconds: Long,
        val endSeconds: Long?,
    )

    /**
     * Parses the header. Returns `null` for malformed input, for `npt=now-`
     * (meaning "from wherever you are"; we simply serve the whole file) and for the
     * `playseek=` variant some vendors use for the same purpose.
     */
    fun parse(header: String?): Request? {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return null

        val npt = when {
            value.startsWith("npt=", ignoreCase = true) -> value.substring(4)
            value.startsWith("playseek=", ignoreCase = true) -> value.substring(9)
            else -> return null
        }.trim()

        if (npt.startsWith("now", ignoreCase = true)) return null

        // "start-end" or "start-end/duration" (or a bare "start-").
        val rangePart = npt.substringBefore('/')
        val dash = rangePart.indexOf('-')
        if (dash < 0) {
            val single = parseNormalPlayTime(rangePart) ?: return null
            return Request(single, null)
        }

        val startText = rangePart.substring(0, dash).trim()
        val endText = rangePart.substring(dash + 1).trim()

        val start = parseNormalPlayTime(startText) ?: return null
        val end = parseNormalPlayTime(endText)
        return Request(start, end)
    }

    /** Byte offset for [startSeconds], assuming an even bitrate across the file. */
    fun toByteOffset(startSeconds: Long, durationMs: Long, totalBytes: Long): Long {
        if (durationMs <= 0L || totalBytes <= 0L) return 0L
        val clamped = startSeconds.coerceAtLeast(0L)
        val fraction = clamped.toDouble() / (durationMs / 1000.0)
        return (fraction * totalBytes).toLong().coerceIn(0L, totalBytes)
    }

    /** `npt=00:01:00.000-00:45:00.000/00:45:00.000` for the response. */
    fun responseHeader(startSeconds: Long, totalDurationSeconds: Long): String {
        val start = startSeconds.coerceAtLeast(0L)
        val total = totalDurationSeconds.coerceAtLeast(0L)
        return "npt=${formatNpt(start)}-${formatNpt(total)}/${formatNpt(total)}"
    }

    /**
     * `npt` allows both `HH:MM:SS.mmm` and plain `SS.mmm`, so both are accepted here.
     */
    fun parseNormalPlayTime(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        if (!trimmed.contains(':')) {
            // Plain seconds, possibly fractional.
            val seconds = trimmed.toDoubleOrNull() ?: return null
            return seconds.toLong().coerceAtLeast(0L)
        }

        // HH:MM:SS[.mmm] – TimeUtils already handles the fractional part.
        return TimeUtils.parseRelTime(trimmed)
    }

    /** `3725` -> `01:02:05.000` */
    private fun formatNpt(totalSeconds: Long): String =
        TimeUtils.toRelTime(totalSeconds) + ".000"
}
