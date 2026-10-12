package com.example.localbeam.data.model

/** A video from the device's MediaStore library. */
data class LocalVideo(
    val id: Long,
    /** Content URI of the file as a string, keeping this model platform independent. */
    val uri: String,
    /**
     * `MediaStore.Video.Media.TITLE`, used where a *presentation* name helps: the
     * DIDL-Lite title shown on the TV and the streaming notification.
     */
    val title: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    /** Directory the file lives in; see [VideoFolders.folderPath]. */
    val folderPath: String,
    /** Label of that directory, e.g. `Camera`. */
    val folderName: String,
    /**
     * The file's own name including its extension (`MediaStore…DISPLAY_NAME`).
     *
     * This is what the picker lists, because `TITLE` is not reliable: MediaStore fills it
     * from the container's metadata when the file has any, and plenty of files carry a
     * title tag that is worse than their name – often a single word.
     */
    val fileName: String = "",
) {
    /**
     * Name the app shows on the phone: the file name without its extension, falling back
     * to [title] when the provider did not report a file name.
     */
    val displayName: String
        get() = fileName.substringBeforeLast('.').trim().takeIf { it.isNotEmpty() } ?: title

    /**
     * Extension used in the streaming URL. Renderers sniff the extension, so a
     * `.mkv` advertised as `.mp4` is asking for trouble.
     *
     * Taken from the MIME type when it is useful, otherwise from the file's own name.
     */
    val extension: String get() = VideoFormats.extensionForMime(mimeType, fileName.ifBlank { title })
}
