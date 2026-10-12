package com.example.localbeam.data.model

/**
 * Watches a renderer that has just been handed a new item, to tell "playing" apart from
 * "accepted the command but refused the media".
 *
 * A successful SOAP response is not proof of playback: a TV can answer
 * `SetAVTransportURI` with `200 OK`, show "this file format is not supported" on screen
 * and sit in `STOPPED`. Feeding that back to the app is the only way it can recover.
 *
 * The grace period exists because a renderer also reports `STOPPED` for a moment right
 * after the command, before the new item starts.
 */
class PlaybackStartWatch(private val graceMs: Long = DEFAULT_GRACE_MS) {

    private var notPlayingSinceMillis: Long? = null

    /**
     * @param state what the renderer reported.
     * @param nowMillis clock reading for that observation.
     * @return `true` it started, `false` it will not play this item, `null` "not yet
     *   clear – keep polling".
     */
    fun observe(state: TransportState, nowMillis: Long): Boolean? {
        if (state.isPlaying || state.isPaused) {
            notPlayingSinceMillis = null
            return true
        }

        if (state == TransportState.TRANSITIONING) {
            // Buffering: it has taken the item.
            notPlayingSinceMillis = null
            return null
        }

        if (state == TransportState.STOPPED || state == TransportState.NO_MEDIA) {
            val since = notPlayingSinceMillis
            if (since == null) {
                notPlayingSinceMillis = nowMillis
                return null
            }
            return if (nowMillis - since >= graceMs) false else null
        }

        // UNKNOWN: the renderer is not answering usefully, so give it the benefit of the
        // doubt rather than restarting playback behind the user's back.
        notPlayingSinceMillis = null
        return null
    }

    companion object {
        const val DEFAULT_GRACE_MS = 2_000L
    }
}
