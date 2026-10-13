package com.viswa.localbeam.cast

import android.content.Context
import android.util.Log
import com.viswa.localbeam.data.model.DlnaDevice
import com.viswa.localbeam.data.model.LocalVideo
import com.viswa.localbeam.data.model.PlaybackStartWatch
import com.viswa.localbeam.data.model.TransportState
import com.viswa.localbeam.data.remote.DidlLite
import com.viswa.localbeam.data.remote.UpnpControlPoint
import com.viswa.localbeam.data.remote.UpnpException
import com.viswa.localbeam.server.LocalServerBridge
import com.viswa.localbeam.server.LocalServerState
import com.viswa.localbeam.server.StreamContent
import com.viswa.localbeam.server.StreamWindow
import com.viswa.localbeam.server.StreamingService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

/** Everything the remote control screen renders. */
data class CastSessionState(
    val device: DlnaDevice? = null,
    val video: LocalVideo? = null,
    val serverUrl: String? = null,
    val serverHost: String? = null,
    /** Content URI of the video the TV is *actually* playing, if any. */
    val castingVideoUri: String? = null,
    val transportState: TransportState = TransportState.UNKNOWN,
    /** Playtime measured from the start of the file, even for a repositioned stream. */
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    /** Seconds into the file the current stream starts at; `0` = the whole file. */
    val streamStartSeconds: Long = 0L,
    val isStarting: Boolean = false,
    val rendererReachable: Boolean = true,
    val error: String? = null,
) {
    /** Duration from the renderer when it knows it, otherwise from MediaStore. */
    val effectiveDurationSeconds: Long
        get() = when {
            durationSeconds > 0L -> durationSeconds
            video != null && video.durationMs > 0L -> video.durationMs / 1000
            else -> 0L
        }

    /** True once our server is bound and the TV has been told what to play. */
    val isCasting: Boolean get() = serverUrl != null

    /**
     * True when the selected video is not the one already on the TV, i.e. the session
     * has to (re)start. Without this, picking a second video while the first is
     * playing looks like "nothing to do" and the TV keeps showing the old one.
     */
    val needsStart: Boolean
        get() {
            val target = video ?: return false
            if (isStarting) return false
            if (serverUrl == null) return true
            return castingVideoUri != target.uri
        }
}

/**
 * Orchestrates a casting session: starts the local video server, hands its URL to
 * the TV over UPnP, and forwards transport commands.
 *
 * It holds the user's selection, so it outlives individual screens and is created
 * once per process in [com.viswa.localbeam.core.AppContainer].
 */
class CastSession(
    private val context: Context,
    private val upnp: UpnpControlPoint,
) {

    private val _state = MutableStateFlow(CastSessionState())

    val state: StateFlow<CastSessionState> = _state.asStateFlow()

    // ------------------------------------------------------------- selection ---

    fun selectDevice(device: DlnaDevice) {
        _state.update { it.copy(device = device, error = null) }
    }

    fun selectVideo(video: LocalVideo) {
        _state.update {
            it.copy(
                video = video,
                durationSeconds = if (video.durationMs > 0L) video.durationMs / 1000 else 0L,
                positionSeconds = 0L,
                error = null,
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    // ----------------------------------------------------------- session start ---

    /**
     * Starts the server, points the renderer at it and presses play.
     *
     * Also used to *switch*: when a session is already live the TV is stopped first,
     * because several renderers ignore a new `SetAVTransportURI` while the previous
     * item is still playing and simply carry on with the old one.
     *
     * Order matters: the server has to be listening before `SetAVTransportURI`,
     * otherwise the renderer probes the URL and gives up.
     */
    suspend fun start(): Result<Unit> {
        val snapshot = _state.value
        val video = snapshot.video
        val device = snapshot.device
        if (video == null) return failure("Pick a video first.")
        if (device == null) return failure("Pick a TV first.")

        _state.update { it.copy(isStarting = true, error = null) }
        return try {
            if (snapshot.isCasting) stopPreviousItem(device)

            repoint(
                device = device,
                video = video,
                startSeconds = 0L,
                durationMs = bestKnownDurationMs(video),
            )
            _state.update {
                it.copy(
                    isStarting = false,
                    transportState = TransportState.PLAYING,
                    rendererReachable = true,
                    error = null,
                )
            }
            Log.i(TAG, "Casting ${video.title} to ${device.friendlyName} via ${_state.value.serverUrl}")
            Result.success(Unit)
        } catch (t: Throwable) {
            // The old stream is gone by now, so do not pretend it is still running.
            _state.update {
                it.copy(
                    isStarting = false,
                    serverUrl = null,
                    serverHost = null,
                    castingVideoUri = null,
                    streamStartSeconds = 0L,
                    positionSeconds = 0L,
                    error = t.userMessage(),
                )
            }
            Result.failure(t)
        }
    }

    /**
     * Serves [video] from [startSeconds] on a URL of its own and points the renderer at it.
     *
     * Every stream gets a unique URL (see [StreamContent.token]) because renderers key
     * their item state on it: Samsung TVs answer "this file format is not supported"
     * when they are handed a URL they have already played and stopped.
     *
     * @return the window the server is actually exposing.
     */
    private suspend fun repoint(
        device: DlnaDevice,
        video: LocalVideo,
        startSeconds: Long,
        durationMs: Long,
    ): StreamWindow {
        StreamingService.start(
            context = context,
            video = video,
            deviceName = device.friendlyName,
            startSeconds = startSeconds,
            durationMs = durationMs,
        )

        val server = awaitServerReady(video, startSeconds)
        val window = server.content.window()
        pointRendererAt(device, server)

        _state.update {
            it.copy(
                serverUrl = server.url,
                serverHost = server.host,
                castingVideoUri = video.uri,
                // The renderer's clock starts where the stream starts, so the offset is
                // kept to turn its positions back into positions in the film.
                streamStartSeconds = window.startSeconds,
                positionSeconds = window.startSeconds,
                durationSeconds = window.startSeconds + window.remainingDurationSeconds,
            )
        }
        return window
    }

    /**
     * Stops whatever the TV is playing and gives it a moment to let the item go.
     *
     * A `SetAVTransportURI` that arrives while the renderer is still tearing down the
     * previous item is ignored or refused, which looks exactly like "the new video never
     * started".
     */
    private suspend fun stopPreviousItem(device: DlnaDevice) {
        runCatching { upnp.stop(device) }
            .onFailure { Log.i(TAG, "Could not stop the previous stream: ${it.message}") }
        delay(RENDERER_SETTLE_MS)
    }

    /** Sends the TV the new item and presses play, retrying once. */
    private suspend fun pointRendererAt(device: DlnaDevice, server: LocalServerState.Ready) {
        val window = server.content.window()
        val metadata = DidlLite.build(
            title = server.content.title,
            url = server.url,
            mimeType = server.content.contentType,
            // For a repositioned stream these describe what is left of the file, which
            // is what the renderer turns into its own timeline.
            sizeBytes = window.length,
            durationMs = window.remainingDurationMs,
        )

        val firstAttempt = runCatching { sendItemToRenderer(device, server.url, metadata) }
        if (firstAttempt.isSuccess) return

        // Renderers that reject the first item after a Stop usually accept the second.
        Log.i(TAG, "The TV refused the new item, retrying: ${firstAttempt.exceptionOrNull()?.message}")
        delay(URI_RETRY_DELAY_MS)
        sendItemToRenderer(device, server.url, metadata)
    }

    private suspend fun sendItemToRenderer(device: DlnaDevice, url: String, metadata: String) {
        upnp.setAvTransportUri(device, url, metadata)
        // Some renderers ignore Play if it arrives in the same millisecond as the URI.
        delay(URI_TO_PLAY_DELAY_MS)
        upnp.play(device)
    }

    /**
     * Waits for the renderer to actually start the item it has just been handed.
     *
     * `false` means it took the command and will not play it: Samsung TVs show "this file
     * format is not supported" and report `STOPPED` while doing so.
     */
    private suspend fun awaitPlaybackStarted(device: DlnaDevice): Boolean {
        val watch = PlaybackStartWatch()
        val deadline = System.currentTimeMillis() + START_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // No answer at all is not evidence of failure, so do not punish the renderer.
            val state = runCatching { upnp.getTransportState(device) }.getOrNull() ?: return true
            when (watch.observe(state, System.currentTimeMillis())) {
                true -> return true
                false -> return false
                null -> delay(START_VERIFY_POLL_MS)
            }
        }
        return true
    }

    /** MediaStore's duration when it has one, otherwise whatever the renderer reported. */
    private fun bestKnownDurationMs(video: LocalVideo): Long = when {
        video.durationMs > 0L -> video.durationMs
        else -> _state.value.durationSeconds * 1000L
    }

    /** Waits for [StreamingService] to bind the server for [video] at [startSeconds]. */
    private suspend fun awaitServerReady(
        video: LocalVideo,
        startSeconds: Long,
    ): LocalServerState.Ready {
        val state = try {
            withTimeout(SERVER_START_TIMEOUT_MS) {
                LocalServerBridge.state.first { current ->
                    current is LocalServerState.Failed ||
                        (
                            current is LocalServerState.Ready &&
                                current.content.uri == video.uri &&
                                current.content.startSeconds == startSeconds
                            )
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw UpnpException("The local server did not start in time.")
        }

        return when (state) {
            is LocalServerState.Ready -> state
            is LocalServerState.Failed -> throw UpnpException(state.message)
            else -> throw UpnpException("The local server is not running.")
        }
    }

    // -------------------------------------------------------------- transport ---

    suspend fun play(): Result<Unit> = command("Play") { device ->
        upnp.play(device)
        TransportState.PLAYING
    }

    suspend fun pause(): Result<Unit> = command("Pause") { device ->
        upnp.pause(device)
        TransportState.PAUSED
    }

    /** Stops playback on the TV but keeps the server (and notification) alive. */
    suspend fun stopPlayback(): Result<Unit> = command("Stop") { device ->
        upnp.stop(device)
        TransportState.STOPPED
    }

    /** Play -> Pause, Pause/Stopped -> Play. */
    suspend fun togglePlayPause(): Result<Unit> =
        if (_state.value.transportState.isPlaying) pause() else play()

    /**
     * Seeks to an absolute position, clamped to the file.
     *
     * Two strategies, in order:
     *  1. UPnP `Seek`, which is instant and exact – but renderers refuse it for some
     *     containers (Matroska being the usual offender) and while they are still
     *     transitioning, answering with UPnP error 701,
     *  2. restarting the stream at the requested position, which no renderer can
     *     refuse because it never has to seek inside the file at all.
     */
    suspend fun seekTo(seconds: Long): Result<Unit> {
        val device = _state.value.device ?: return failure("No TV selected.")
        val target = clampToDuration(seconds)
        _state.update { it.copy(error = null) }

        if (tryNativeSeek(device, target)) {
            // Optimistic: the poll a second from now will correct any drift.
            _state.update { it.copy(positionSeconds = target, error = null) }
            return Result.success(Unit)
        }

        Log.i(TAG, "The TV refused a seek to ${target}s; repositioning the stream instead")
        return reposition(device, target)
    }

    /** Fast-forward/rewind by [deltaSeconds] (negative rewinds). */
    suspend fun seekBy(deltaSeconds: Long): Result<Unit> =
        seekTo(_state.value.positionSeconds + deltaSeconds)

    /**
     * One UPnP `Seek`, in the current stream's own timeline.
     *
     * `false` means "this TV will not seek here" – never an exception, so the caller
     * can fall back to [reposition]. Two refusals are expected and handled: the
     * renderer is still transitioning (701), or the media is not seekable to it.
     */
    private suspend fun tryNativeSeek(device: DlnaDevice, target: Long): Boolean {
        val streamStart = _state.value.streamStartSeconds
        // The renderer's timeline begins where the stream does, so anything before that
        // is out of reach and needs a new window.
        if (target < streamStart) return false

        val relative = target - streamStart
        awaitSeekableState(device)

        if (attemptSeek(device, relative)) return true
        // The classic "still starting up" 701 is transient, so try once more.
        delay(SEEK_RETRY_DELAY_MS)
        return attemptSeek(device, relative)
    }

    private suspend fun attemptSeek(device: DlnaDevice, relativeSeconds: Long): Boolean =
        runCatching { upnp.seekTo(device, relativeSeconds) }
            .onFailure { Log.i(TAG, "Seek to ${relativeSeconds}s refused: ${it.message}") }
            .isSuccess

    /**
     * Waits (briefly) until the renderer reports a state in which `Seek` is legal.
     *
     * Best effort only: an unreachable or unusual renderer is given the Seek anyway,
     * because refusing to try would break the TVs that do not answer `GetTransportInfo`.
     */
    private suspend fun awaitSeekableState(device: DlnaDevice) {
        val deadline = System.currentTimeMillis() + SEEK_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { upnp.getTransportState(device) }.getOrNull() ?: return
            if (state.isPlaying || state.isPaused) return
            delay(SEEK_STATE_POLL_MS)
        }
    }

    /**
     * Restarts the stream at [target] and points the TV at it.
     *
     * This is what makes seeking work on renderers that cannot seek a given file: the
     * server exposes the file *from* [target], so the TV receives a fresh item that
     * begins exactly where the user asked. Byte offsets are estimated from the
     * duration, so the landing point is approximate for variable-bitrate files – which
     * is why a real UPnP `Seek` is always tried first.
     */
    private suspend fun reposition(device: DlnaDevice, target: Long): Result<Unit> {
        val snapshot = _state.value
        val video = snapshot.video ?: return failure("Pick a video first.")
        val durationMs = snapshot.effectiveDurationSeconds * 1000L
        if (durationMs <= 0L) {
            return failure("This TV will not seek inside this file, and its length is unknown.")
        }

        _state.update { it.copy(isStarting = true, error = null) }
        return try {
            stopPreviousItem(device)
            val window = repoint(device, video, startSeconds = target, durationMs = durationMs)

            if (awaitPlaybackStarted(device)) {
                _state.update {
                    it.copy(
                        isStarting = false,
                        transportState = TransportState.PLAYING,
                        rendererReachable = true,
                        error = null,
                    )
                }
                Log.i(TAG, "Repositioned the stream to ${window.startSeconds}s (byte ${window.offsetBytes})")
                return Result.success(Unit)
            }

            // The renderer acknowledged the command and then refused the media. Put the
            // whole file back, so the film is never left stuck on the TV's error screen.
            Log.w(TAG, "The TV refused the stream repositioned to ${target}s; serving the whole file")
            repoint(device, video, startSeconds = 0L, durationMs = durationMs)
            val message = "Your TV would not play from that position, so playback restarted at the " +
                "beginning. This file is one it can play but not seek inside – remuxing it to " +
                "MP4 makes seeking work."
            _state.update {
                it.copy(
                    isStarting = false,
                    transportState = TransportState.PLAYING,
                    rendererReachable = true,
                    error = message,
                )
            }
            Result.failure(IllegalStateException(message))
        } catch (t: Throwable) {
            _state.update { it.copy(isStarting = false, error = t.userMessage()) }
            Result.failure(t)
        }
    }

    /**
     * Reads the renderer's own state. Called once a second by the remote screen so
     * the UI reflects what the TV is actually doing (including pauses made with the
     * TV remote).
     */
    suspend fun refreshStatus(): Result<Unit> {
        val device = _state.value.device ?: return Result.success(Unit)
        return try {
            val transport = upnp.getTransportState(device)
            val position = upnp.getPosition(device)
            _state.update { current ->
                val streamStart = current.streamStartSeconds
                current.copy(
                    transportState = transport,
                    // A repositioned stream reports time from its own start, so add the
                    // offset back to show a position in the film.
                    positionSeconds = streamStart + position.positionSeconds,
                    durationSeconds = when {
                        position.durationSeconds <= 0L -> current.durationSeconds
                        else -> streamStart + position.durationSeconds
                    },
                    rendererReachable = true,
                )
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            _state.update { it.copy(rendererReachable = false) }
            Result.failure(t)
        }
    }

    /**
     * Ends the session: stops the TV, shuts the local server down and drops the
     * video (the TV selection is kept so the next video can go to the same screen).
     */
    suspend fun endSession() {
        val device = _state.value.device
        if (device != null) {
            runCatching { upnp.stop(device) }
        }
        StreamingService.stop(context)
        _state.update {
            it.copy(
                video = null,
                serverUrl = null,
                serverHost = null,
                castingVideoUri = null,
                streamStartSeconds = 0L,
                transportState = TransportState.STOPPED,
                positionSeconds = 0L,
                durationSeconds = 0L,
                error = null,
            )
        }
    }

    // ----------------------------------------------------------------- helpers ---

    private suspend fun command(
        label: String,
        block: suspend (DlnaDevice) -> TransportState,
    ): Result<Unit> {
        val device = _state.value.device ?: return failure("No TV selected.")
        return try {
            val result = block(device)
            _state.update { it.copy(transportState = result, error = null) }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "$label failed", t)
            _state.update { it.copy(error = t.userMessage()) }
            Result.failure(t)
        }
    }

    private fun clampToDuration(seconds: Long): Long {
        val duration = _state.value.effectiveDurationSeconds
        return if (duration > 0L) {
            seconds.coerceIn(0L, maxOf(0L, duration - 1L))
        } else {
            seconds.coerceAtLeast(0L)
        }
    }

    private fun failure(message: String): Result<Unit> {
        _state.update { it.copy(error = message) }
        return Result.failure(IllegalStateException(message))
    }

    private fun Throwable.userMessage(): String = when (this) {
        is UpnpException -> message ?: "The TV rejected the request."
        is SecurityException -> "Missing permission: " + (message ?: "denied")
        is TimeoutCancellationException -> "The TV did not answer in time."
        else -> message ?: this::class.java.simpleName
    }

    private companion object {
        const val TAG = "CastSession"
        const val SERVER_START_TIMEOUT_MS = 8_000L

        /** Renderers want a beat between `Stop` and the next `SetAVTransportURI`. */
        const val RENDERER_SETTLE_MS = 600L
        const val URI_TO_PLAY_DELAY_MS = 350L
        const val URI_RETRY_DELAY_MS = 500L

        /** How long a newly handed item gets to reach PLAYING before we call it refused. */
        const val START_VERIFY_TIMEOUT_MS = 6_000L
        const val START_VERIFY_POLL_MS = 250L

        const val SEEK_RETRY_DELAY_MS = 700L
        const val SEEK_READY_TIMEOUT_MS = 2_000L
        const val SEEK_STATE_POLL_MS = 250L
    }
}
