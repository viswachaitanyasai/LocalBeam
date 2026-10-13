package com.viswa.localbeam.server

/** What to do with a request's `Range` header, resolved against the file size. */
sealed interface RangeRequest {

    /** Serve `[start, endInclusive]` as `206 Partial Content`. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeRequest {
        val length: Long get() = endInclusive - start + 1
    }

    /** Serve the whole file as `200 OK`. */
    data object Full : RangeRequest

    /** `416`: the client asked for bytes beyond the end of the file. */
    data object Unsatisfiable : RangeRequest
}

/**
 * Parses single-range `bytes=` headers. Pure Kotlin, so the edge cases (open-ended
 * ranges, suffix ranges, garbage from a TV's HTTP stack) are unit tested.
 *
 * Deliberate simplifications, both of which are legal HTTP:
 *  - multi-range requests (`bytes=0-99,200-299`) are answered with the full file
 *    instead of a `multipart/byteranges` body; DLNA renderers send a single range,
 *  - unknown units are treated as "no range".
 */
object RangeSpec {

    private const val PREFIX = "bytes="

    fun parse(header: String?, totalLength: Long): RangeRequest {
        val value = header?.trim().orEmpty()
        if (value.isEmpty()) return RangeRequest.Full
        if (totalLength <= 0L) return RangeRequest.Full

        val spec = value.substringBefore(';').trim()
        if (!spec.startsWith(PREFIX, ignoreCase = true)) return RangeRequest.Full

        val ranges = spec.substring(PREFIX.length).trim()
        // Multiple ranges: serve everything rather than a partial body we cannot frame.
        if (ranges.contains(',')) return RangeRequest.Full

        val dash = ranges.indexOf('-')
        if (dash < 0) return RangeRequest.Full

        val startText = ranges.substring(0, dash).trim()
        val endText = ranges.substring(dash + 1).trim()

        // "-500": the last 500 bytes.
        if (startText.isEmpty()) {
            val suffix = endText.toLongOrNull() ?: return RangeRequest.Full
            if (suffix <= 0L) return RangeRequest.Unsatisfiable
            if (suffix >= totalLength) return RangeRequest.Partial(0L, totalLength - 1)
            return RangeRequest.Partial(totalLength - suffix, totalLength - 1)
        }

        val start = startText.toLongOrNull() ?: return RangeRequest.Full
        if (start < 0L) return RangeRequest.Unsatisfiable
        if (start >= totalLength) return RangeRequest.Unsatisfiable

        // "500-": from 500 to the end.
        if (endText.isEmpty()) return RangeRequest.Partial(start, totalLength - 1)

        val end = endText.toLongOrNull() ?: return RangeRequest.Partial(start, totalLength - 1)
        if (end < start) return RangeRequest.Unsatisfiable

        // A renderer that asked for more than exists gets what exists, per RFC 7233.
        return RangeRequest.Partial(start, minOf(end, totalLength - 1))
    }

    /** `bytes 0-1023/2048`, the header a `206` response carries. */
    fun contentRange(start: Long, endInclusive: Long, totalLength: Long): String =
        "bytes $start-$endInclusive/$totalLength"

    /** `bytes <star>/2048` (RFC 7233's unsatisfied-range form) for a `416` response. */
    fun unsatisfiableContentRange(totalLength: Long): String = "bytes */$totalLength"
}
