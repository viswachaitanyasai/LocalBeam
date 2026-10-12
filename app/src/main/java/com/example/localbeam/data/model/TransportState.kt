package com.example.localbeam.data.model

/**
 * UPnP AVTransport states, mapped from the strings a renderer returns in
 * `GetTransportInfo` / `GetTransportInfo` events.
 */
enum class TransportState {
    STOPPED,
    PLAYING,
    PAUSED,
    TRANSITIONING,
    NO_MEDIA,
    UNKNOWN,
    ;

    val isPlaying: Boolean get() = this == PLAYING
    val isPaused: Boolean get() = this == PAUSED
    val isActive: Boolean get() = this == PLAYING || this == PAUSED || this == TRANSITIONING

    /** "Playing", "Paused", … for the status line. */
    val label: String
        get() = when (this) {
            STOPPED -> "Stopped"
            PLAYING -> "Playing"
            PAUSED -> "Paused"
            TRANSITIONING -> "Buffering…"
            NO_MEDIA -> "No media"
            UNKNOWN -> "Unknown"
        }

    companion object {
        /** Renderer states: `PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`, `TRANSITIONING`, … */
        fun fromUpnp(value: String?): TransportState {
            val state = value?.trim()?.uppercase() ?: return UNKNOWN
            return when {
                state.contains("PAUSE") -> PAUSED
                state.contains("PLAY") -> PLAYING
                state.contains("STOP") -> STOPPED
                state.contains("TRANSITION") || state.contains("BUFFER") -> TRANSITIONING
                state.contains("NO_MEDIA") -> NO_MEDIA
                else -> UNKNOWN
            }
        }
    }
}

/** Position/duration as reported by the renderer. */
data class RendererPosition(
    val positionSeconds: Long,
    val durationSeconds: Long,
)
