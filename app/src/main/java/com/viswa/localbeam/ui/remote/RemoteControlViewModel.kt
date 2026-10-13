package com.viswa.localbeam.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viswa.localbeam.cast.CastSession
import com.viswa.localbeam.core.appContainer
import com.viswa.localbeam.data.model.TransportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class RemoteControlUiState(
    val deviceName: String? = null,
    val videoTitle: String? = null,
    val serverUrl: String? = null,
    val transportState: TransportState = TransportState.UNKNOWN,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val isStarting: Boolean = false,
    val isBusy: Boolean = false,
    val rendererReachable: Boolean = true,
    val error: String? = null,
) {
    val isPlaying: Boolean get() = transportState.isPlaying
    val isCasting: Boolean get() = serverUrl != null

    val statusLabel: String
        get() = when {
            isStarting -> "Starting the stream…"
            !rendererReachable -> "TV not responding"
            else -> transportState.label
        }
}

/**
 * Screen 3's brain.
 *
 * Kicks off the session when the screen appears, forwards transport commands, and
 * polls the renderer once a second so the UI matches what the TV is really doing
 * (pauses made with the TV's own remote show up too).
 */
class RemoteControlViewModel(application: Application) : AndroidViewModel(application) {

    private val session: CastSession = application.appContainer.castSession

    private val busy = MutableStateFlow(false)
    private var pollJob: Job? = null

    val uiState: StateFlow<RemoteControlUiState> =
        combine(session.state, busy) { state, isBusy ->
            RemoteControlUiState(
                deviceName = state.device?.friendlyName,
                // The same name the picker listed, so the two screens agree.
                videoTitle = state.video?.displayName,
                serverUrl = state.serverUrl,
                transportState = state.transportState,
                positionSeconds = state.positionSeconds,
                durationSeconds = state.effectiveDurationSeconds,
                isStarting = state.isStarting,
                isBusy = isBusy,
                rendererReachable = state.rendererReachable,
                error = state.error,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteControlUiState())

    /**
     * Called when the screen is shown: starts the stream when the pick changed, which
     * covers both the first cast and switching to another video mid-session.
     */
    fun onScreenEntered() {
        if (session.state.value.needsStart) {
            viewModelScope.launch { runAction { session.start() } }
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch { runAction { session.togglePlayPause() } }
    }

    fun seekBy(deltaSeconds: Long) {
        viewModelScope.launch { runAction { session.seekBy(deltaSeconds) } }
    }

    /** Called once the user releases the progress slider. */
    fun seekTo(seconds: Long) {
        viewModelScope.launch { runAction { session.seekTo(seconds) } }
    }

    /** Stops playback on the TV but keeps the local server running. */
    fun stopPlayback() {
        viewModelScope.launch { runAction { session.stopPlayback() } }
    }

    /** Stops the TV, shuts the server down and clears the session. */
    fun endSession(onFinished: () -> Unit = {}) {
        viewModelScope.launch {
            runAction { session.endSession(); Result.success(Unit) }
            onFinished()
        }
    }

    fun dismissError() = session.clearError()

    // ---------------------------------------------------------------- polling ---

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (session.state.value.isCasting) {
                    // Failures are surfaced through rendererReachable, not as errors.
                    session.refreshStatus()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun runAction(action: suspend () -> Result<Unit>) {
        busy.value = true
        try {
            action()
        } finally {
            busy.value = false
        }
    }

    override fun onCleared() {
        stopPolling()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
