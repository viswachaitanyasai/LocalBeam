package com.example.localbeam.core

import java.util.Locale

/**
 * Time helpers for the two formats UPnP speaks:
 *  - `HH:MM:SS` for `Seek` targets and `GetPositionInfo` responses,
 *  - `HH:MM:SS.mmm` for DLNA `TimeSeekRange.dlna.org` headers.
 */
object TimeUtils {

    /** `3725` -> `"01:02:05"`. Returns `"00:00:00"` for negative input. */
    fun toRelTime(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            safe / 3600,
            (safe % 3600) / 60,
            safe % 60,
        )
    }

    /** `"01:02:05"`, `"1:02:05.500"`, `"00:01:02"` -> seconds. `null` when unparsable. */
    fun parseRelTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val trimmed = value.trim()
        // UPnP uses "NOT_IMPLEMENTED" / "00:00:00" for unknown values.
        if (trimmed.startsWith("NOT_IMPLEMENTED", ignoreCase = true)) return null

        val withoutFraction = trimmed.substringBefore('.')
        val parts = withoutFraction.split(':')
        val numbers = parts.map { it.trim().toLongOrNull() ?: return null }

        return when (numbers.size) {
            3 -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
            2 -> numbers[0] * 60 + numbers[1]
            1 -> numbers[0]
            else -> null
        }
    }

    /** `3725000` ms -> `"01:02:05"`, or `"--:--"` when the duration is unknown. */
    fun formatDuration(durationMs: Long): String =
        if (durationMs <= 0L) "--:--" else toRelTime(durationMs / 1000)

    /** `12345678` bytes -> `"11.8 MB"`. */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "—"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) {
            "${value.toLong()} ${units[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unit])
        }
    }

    /** `123` -> `"2:03"`, used by the progress read-out on the remote control. */
    fun formatClock(totalSeconds: Long): String {
        val safe = if (totalSeconds < 0) 0 else totalSeconds
        return String.format(Locale.US, "%d:%02d", safe / 60, safe % 60)
    }
}
