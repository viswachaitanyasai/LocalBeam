package com.example.localbeam.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localbeam.core.appContainer
import com.example.localbeam.ui.folders.FolderPickerScreen
import com.example.localbeam.ui.picker.VideoPickerScreen
import com.example.localbeam.ui.remote.RemoteControlScreen
import com.example.localbeam.ui.scanner.DeviceScannerScreen

/** The steps of a casting session. */
enum class LocalBeamScreen { Devices, Folders, Videos, Remote }

/**
 * The whole app: TV discovery -> folder -> video -> remote control.
 *
 * The flow is strictly linear, so it is expressed as one saveable enum instead of
 * dragging in a navigation library. The selected TV and video live in
 * [com.example.localbeam.cast.CastSession], and the picker state (loaded library +
 * open folder) lives in the shared picker ViewModel, so both outlive these screens.
 */
@Composable
fun LocalBeamApp() {
    val context = LocalContext.current
    val castSession = remember(context) { context.appContainer.castSession }
    val sessionState by castSession.state.collectAsStateWithLifecycle()

    var currentScreen by rememberSaveable { mutableStateOf(LocalBeamScreen.Devices.name) }
    val screen = LocalBeamScreen.valueOf(currentScreen)

    // Coming back from the notification while a stream is live should land on the remote.
    LaunchedEffect(Unit) {
        if (sessionState.isCasting) currentScreen = LocalBeamScreen.Remote.name
    }

    BackHandler(enabled = screen != LocalBeamScreen.Devices) {
        currentScreen = when (screen) {
            LocalBeamScreen.Remote -> LocalBeamScreen.Videos.name
            LocalBeamScreen.Videos -> LocalBeamScreen.Folders.name
            else -> LocalBeamScreen.Devices.name
        }
    }

    when (screen) {
        LocalBeamScreen.Devices -> DeviceScannerScreen(
            onDeviceSelected = { currentScreen = LocalBeamScreen.Folders.name },
        )

        LocalBeamScreen.Folders -> FolderPickerScreen(
            deviceName = sessionState.device?.friendlyName,
            onFolderSelected = { currentScreen = LocalBeamScreen.Videos.name },
            onBack = { currentScreen = LocalBeamScreen.Devices.name },
        )

        LocalBeamScreen.Videos -> VideoPickerScreen(
            deviceName = sessionState.device?.friendlyName,
            onVideoSelected = { currentScreen = LocalBeamScreen.Remote.name },
            onBack = { currentScreen = LocalBeamScreen.Folders.name },
        )

        LocalBeamScreen.Remote -> RemoteControlScreen(
            onBack = { currentScreen = LocalBeamScreen.Videos.name },
            onSessionEnded = { currentScreen = LocalBeamScreen.Videos.name },
        )
    }
}
