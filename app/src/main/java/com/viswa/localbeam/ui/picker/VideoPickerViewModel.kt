package com.viswa.localbeam.ui.picker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.viswa.localbeam.core.MediaPermissions
import com.viswa.localbeam.core.appContainer
import com.viswa.localbeam.data.model.LocalVideo
import com.viswa.localbeam.data.model.VideoFolder
import com.viswa.localbeam.data.model.VideoFolders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoPickerUiState(
    val isLoading: Boolean = false,
    val videos: List<LocalVideo> = emptyList(),
    /** Videos grouped by directory, with a virtual "All videos" entry first. */
    val folders: List<VideoFolder> = emptyList(),
    /** Directory being browsed, or `null`/`VideoFolders.ALL_PATH` for everything. */
    val selectedFolderPath: String? = null,
    val error: String? = null,
    /** True when the media permission is missing and the UI should ask for it. */
    val needsPermission: Boolean = false,
    val hasLoaded: Boolean = false,
) {
    val isEmpty: Boolean get() = hasLoaded && !isLoading && !needsPermission && videos.isEmpty()

    /** The folder the user opened, if any. */
    val selectedFolder: VideoFolder?
        get() = folders.firstOrNull { it.path == selectedFolderPath }

    /** Videos to show on the video list: the selected folder's, or all of them. */
    val visibleVideos: List<LocalVideo>
        get() = selectedFolder?.videos ?: videos

    val folderLabel: String get() = selectedFolder?.name ?: "All videos"

    val folderLocation: String
        get() = selectedFolder?.breadcrumb ?: "Every video on this device"
}

/**
 * Lists local videos through MediaStore and groups them into folders.
 *
 * Nothing is read from disk here beyond metadata, and the query only runs once the
 * runtime permission is in place. One instance serves both the folder screen and the
 * video list (both are scoped to the activity's `ViewModelStore`), so opening a folder
 * does not re-query MediaStore.
 */
class VideoPickerViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.appContainer

    private val _uiState = MutableStateFlow(VideoPickerUiState())
    val uiState: StateFlow<VideoPickerUiState> = _uiState.asStateFlow()

    /** Loads the library unless it is already loaded (both picker screens call this). */
    fun loadIfNeeded() {
        val state = _uiState.value
        if (state.hasLoaded || state.isLoading) return
        load()
    }

    /** Queries the library, or flips [VideoPickerUiState.needsPermission] on. */
    fun load() {
        if (_uiState.value.isLoading) return

        val context = getApplication<Application>()
        if (!MediaPermissions.hasVideoAccess(context)) {
            _uiState.update {
                it.copy(isLoading = false, needsPermission = true, hasLoaded = true, error = null)
            }
            return
        }

        _uiState.update { it.copy(isLoading = true, needsPermission = false, error = null) }
        viewModelScope.launch {
            try {
                val videos = container.videoSource.queryVideos()
                _uiState.update { current ->
                    val folders = VideoFolders.group(videos)
                    current.copy(
                        isLoading = false,
                        videos = videos,
                        folders = folders,
                        // Keep the user's folder if it still exists after a refresh.
                        selectedFolderPath = current.selectedFolderPath
                            ?.takeIf { path -> folders.any { it.path == path } },
                        hasLoaded = true,
                        error = null,
                    )
                }
            } catch (security: SecurityException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasLoaded = true,
                        needsPermission = true,
                        error = "Access to your videos was denied.",
                    )
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasLoaded = true,
                        error = t.message ?: "Could not read the video library.",
                    )
                }
            }
        }
    }

    /** Called after the permission dialog is answered. */
    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            load()
        } else {
            _uiState.update {
                it.copy(
                    needsPermission = true,
                    isLoading = false,
                    hasLoaded = true,
                    error = "LocalBeam needs access to your videos to stream them.",
                )
            }
        }
    }

    /** Opens a folder (`null` or [VideoFolders.ALL_PATH] means "all videos"). */
    fun selectFolder(folderPath: String?) {
        _uiState.update { it.copy(selectedFolderPath = folderPath) }
    }

    fun selectVideo(video: LocalVideo) {
        container.castSession.selectVideo(video)
    }
}
