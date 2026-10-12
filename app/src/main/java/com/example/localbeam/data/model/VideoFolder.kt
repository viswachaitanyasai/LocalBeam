package com.example.localbeam.data.model

/**
 * A folder in the device's video library, used by the folder-first picker.
 *
 * Videos are grouped by the directory they live in, which is what people actually
 * browse by ("Camera", "Movies", "Downloads") rather than by a flat list of
 * everything on the device.
 */
data class VideoFolder(
    /** Directory path, [VideoFolders.ALL_PATH] for the virtual "every video" entry. */
    val path: String,
    val name: String,
    /** Where the folder lives, e.g. `DCIM` under internal storage. */
    val breadcrumb: String,
    val videos: List<LocalVideo>,
) {
    val videoCount: Int get() = videos.size

    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }

    /** Newest video in the folder (the source list is newest first), for the thumbnail. */
    val preview: LocalVideo? get() = videos.firstOrNull()

    val isAllVideos: Boolean get() = path == VideoFolders.ALL_PATH
}

/**
 * Groups videos into folders and describes where those folders live.
 *
 * Pure Kotlin: the path handling is the fiddly part here (internal storage, SD cards,
 * providers that only hand back a bucket name), so it is unit tested rather than
 * discovered by scrolling a phone.
 */
object VideoFolders {

    /** Virtual folder holding every video. */
    const val ALL_PATH = "*all*"

    /** Key for videos whose folder could not be determined. */
    const val UNKNOWN_PATH = ""

    private const val PRIMARY_ROOT = "/storage/emulated/0"

    /** `/storage/<volume-id>` – the root of a mounted volume. */
    private val VOLUME_ROOT = Regex("^/storage/[^/]+$")

    /**
     * Folder key for one MediaStore row.
     *
     * `DATA` (the full path) is preferred: it is populated for every indexed video on
     * every supported release. `RELATIVE_PATH` (Android 10+) and the bucket name are
     * the fallbacks for ROMs that withhold it.
     */
    fun folderPath(dataPath: String?, relativePath: String?, bucketName: String?): String {
        val data = dataPath?.trim().orEmpty()
        if (data.isNotEmpty()) {
            val parent = data.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) return parent
        }

        val relative = relativePath?.trim()?.trimEnd('/').orEmpty()
        if (relative.isNotEmpty()) return relative

        return bucketName?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN_PATH
    }

    /** Folder label shown in the list, e.g. `Camera`. */
    fun folderName(path: String, bucketName: String?): String {
        if (path.isBlank()) return "Other videos"
        val cleaned = path.trimEnd('/')
        // A video in the root of a volume lives in a folder called "Internal storage",
        // not "0" (the last segment of /storage/emulated/0).
        volumeLabel(cleaned)?.let { return it }

        val segment = cleaned.substringAfterLast('/')
        if (segment.isNotEmpty()) return segment
        return bucketName?.trim()?.takeIf { it.isNotEmpty() } ?: "Videos"
    }

    /**
     * Where the folder sits, e.g. `DCIM` for `/storage/emulated/0/DCIM/Camera`,
     * `Internal storage` for a folder at the root of primary storage, or
     * `SD card/DCIM` on a removable volume.
     */
    fun breadcrumb(path: String): String {
        if (path.isBlank()) return "Uncategorised"
        // Fallback keys are plain names, not paths.
        if (!path.startsWith("/")) return "Videos"

        val cleaned = path.trimEnd('/')
        volumeLabel(cleaned)?.let { return it }

        val parent = cleaned.substringBeforeLast('/', "")
        return when {
            parent == PRIMARY_ROOT -> "Internal storage"
            parent.startsWith("$PRIMARY_ROOT/") -> parent.removePrefix("$PRIMARY_ROOT/")
            parent.startsWith("/storage/") -> {
                val volumeRelative = parent.removePrefix("/storage/").substringAfter('/', "")
                if (volumeRelative.isBlank()) "SD card" else "SD card/$volumeRelative"
            }
            parent.isBlank() || parent == "/" -> "Internal storage"
            else -> parent
        }
    }

    /** Readable name for a volume root, or `null` when [path] is an ordinary folder. */
    private fun volumeLabel(path: String): String? = when {
        path == PRIMARY_ROOT -> "Internal storage"
        VOLUME_ROOT.matches(path) && !path.endsWith("/emulated") -> "SD card"
        else -> null
    }

    /**
     * Groups [videos] (newest first, as MediaStore returns them) into folders.
     *
     * @param includeAll prepends the virtual "All videos" entry.
     */
    fun group(videos: List<LocalVideo>, includeAll: Boolean = true): List<VideoFolder> {
        val folders = videos
            .groupBy { it.folderPath }
            .map { (path, items) ->
                VideoFolder(
                    path = path,
                    name = items.first().folderName,
                    breadcrumb = breadcrumb(path),
                    videos = items,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

        if (!includeAll || videos.isEmpty()) return folders

        return listOf(
            VideoFolder(
                path = ALL_PATH,
                name = "All videos",
                breadcrumb = "Every video on this device",
                videos = videos,
            ),
        ) + folders
    }
}
