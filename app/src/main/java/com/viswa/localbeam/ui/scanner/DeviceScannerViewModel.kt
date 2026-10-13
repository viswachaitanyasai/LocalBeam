package com.viswa.localbeam.ui.scanner

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viswa.localbeam.core.CastConstants
import com.viswa.localbeam.core.NetworkUtils
import com.viswa.localbeam.core.appContainer
import com.viswa.localbeam.data.model.DlnaDevice
import com.viswa.localbeam.data.remote.DiscoveryEvent
import com.viswa.localbeam.server.LocalServerBridge
import com.viswa.localbeam.server.LocalServerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DeviceScannerUiState(
    val isScanning: Boolean = false,
    val devices: List<DlnaDevice> = emptyList(),
    val message: String? = null,
    val error: String? = null,
    val localAddress: String? = null,
    val isHotspot: Boolean = false,
    val serverState: LocalServerState = LocalServerState.Idle,
    val hasScanned: Boolean = false,
) {
    /** "192.168.43.1 · hotspot" or "no network address". */
    val localAddressLabel: String
        get() = when {
            localAddress == null -> "No hotspot or Wi-Fi address"
            isHotspot -> "$localAddress · hotspot"
            else -> "$localAddress · Wi-Fi"
        }
}

/**
 * Drives SSDP discovery.
 *
 * A scan first asks for MediaRenderers specifically. If nothing answers, it retries
 * with `ssdp:all` and filters the replies locally – some TVs only answer the generic
 * search.
 */
class DeviceScannerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    private val _uiState = MutableStateFlow(DeviceScannerUiState())
    val uiState: StateFlow<DeviceScannerUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        // Reflect the state of the streaming server while the user is on this screen.
        viewModelScope.launch {
            LocalServerBridge.state.collect { server ->
                _uiState.update { it.copy(serverState = server) }
            }
        }
        refreshNetworkInfo()
    }

    fun refreshNetworkInfo() {
        viewModelScope.launch {
            val candidate = NetworkUtils.localIpv4(getApplication())
            _uiState.update {
                it.copy(
                    localAddress = candidate?.hostAddress,
                    isHotspot = NetworkUtils.looksLikeHotspot(candidate),
                )
            }
        }
    }

    fun scan() {
        scanJob?.cancel()
        refreshNetworkInfo()
        _uiState.update {
            it.copy(
                isScanning = true,
                devices = emptyList(),
                error = null,
                hasScanned = true,
                message = "Searching for DLNA TVs on your network…",
            )
        }

        scanJob = viewModelScope.launch {
            val found = linkedMapOf<String, DlnaDevice>()

            collectResults(found, listOf(CastConstants.ST_MEDIA_RENDERER))

            if (found.isEmpty() && isActive) {
                _uiState.update {
                    it.copy(message = "No MediaRenderer replied. Trying a wider search…")
                }
                collectResults(found, listOf(CastConstants.ST_ALL))
            }

            _uiState.update {
                it.copy(
                    isScanning = false,
                    message = if (found.isEmpty()) {
                        "No DLNA TVs found. Make sure the TV is on, on the same network, and has its " +
                            "DLNA/UPnP setting enabled."
                    } else {
                        null
                    },
                )
            }
        }
    }

    fun cancelScan() {
        scanJob?.cancel()
        _uiState.update { it.copy(isScanning = false, message = "Scan cancelled.") }
    }

    /** Remembers the TV for the next screens. */
    fun selectDevice(device: DlnaDevice) {
        container.castSession.selectDevice(device)
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    private suspend fun collectResults(
        found: MutableMap<String, DlnaDevice>,
        targets: List<String>,
    ) {
        container.discoveryClient.discover(targets = targets).collect { event ->
            when (event) {
                is DiscoveryEvent.DeviceFound -> {
                    found[event.device.id] = event.device
                    _uiState.update { it.copy(devices = found.values.toList(), message = null) }
                }

                is DiscoveryEvent.Failed -> _uiState.update { it.copy(error = event.message) }
                is DiscoveryEvent.Completed -> Unit
            }
        }
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}
