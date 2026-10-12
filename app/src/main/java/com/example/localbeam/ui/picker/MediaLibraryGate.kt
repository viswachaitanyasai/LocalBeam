package com.example.localbeam.ui.picker

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localbeam.core.MediaPermissions

/**
 * Loads the video library and asks for the media permission when it is missing.
 *
 * Both picker screens (folders and videos) call this because they share one
 * [VideoPickerViewModel]: the first one to compose starts the load and shows the
 * permission dialog, the second finds the work already done. It also covers a
 * process restart that lands directly on the video list.
 *
 * @return a callback that re-requests the permission, for a "Grant access" button.
 */
@Composable
fun MediaLibraryGate(viewModel: VideoPickerViewModel): () -> Unit {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onPermissionResult(result.values.all { it })
    }

    val requestPermission: () -> Unit = remember(launcher) {
        { launcher.launch(MediaPermissions.videoLibrary) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadIfNeeded()
    }

    // Fires when the answer turns out to be "no": the state flips to
    // needsPermission = true, which is the moment a second prompt makes sense.
    LaunchedEffect(state.needsPermission) {
        if (state.needsPermission) requestPermission()
    }

    return requestPermission
}
