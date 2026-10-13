package com.viswa.localbeam.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of the embedded server, as seen from the UI. */
sealed interface LocalServerState {

    /** Nothing is being served. */
    data object Idle : LocalServerState

    /** The server is bound and reachable at [url]. */
    data class Ready(
        val url: String,
        val host: String,
        val port: Int,
        val content: StreamContent,
    ) : LocalServerState

    /** Binding failed; [message] is shown to the user. */
    data class Failed(val message: String) : LocalServerState
}

/**
 * Hands the server's state from [StreamingService] to the UI layer.
 *
 * The service and the activities live in the same process, so a `StateFlow` is
 * enough – no binder or Messenger plumbing, and no chance of a stale binding.
 * (If the service ever moves to `:remote`, this is the piece to replace.)
 */
object LocalServerBridge {

    private val _state = MutableStateFlow<LocalServerState>(LocalServerState.Idle)

    /** Hot stream of the current server state. */
    val state: StateFlow<LocalServerState> = _state.asStateFlow()

    /** Current value, for non-collecting callers. */
    val current: LocalServerState get() = _state.value

    internal fun publish(state: LocalServerState) {
        _state.value = state
    }
}
