package com.example.localbeam.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The folder picker groups videos by directory, which means path handling for internal
 * storage, SD cards and providers that only report a bucket name.
 */
class VideoFoldersTest {

    private fun video(
        id: Long,
        title: String,
        folderPath: String,
        sizeBytes: Long = 1_000L,
    ) = LocalVideo(
        id = id,
        uri = "content://media/external/video/media/$id",
        title = title,
        mimeType = "video/mp4",
        sizeBytes = sizeBytes,
        durationMs = 60_000L,
        folderPath = folderPath,
        folderName = VideoFolders.folderName(folderPath, null),
    )

    // ------------------------------------------------------------- path resolution ---

    @Test
    fun `folder path comes from the full file path`() {
        assertEquals(
            "/storage/emulated/0/DCIM/Camera",
            VideoFolders.folderPath("/storage/emulated/0/DCIM/Camera/vid.mp4", null, "Camera"),
        )
    }

    @Test
    fun `relative path is used when the full path is withheld`() {
        assertEquals(
            "Movies/Trips",
            VideoFolders.folderPath(null, "Movies/Trips/", "Trips"),
        )
    }

    @Test
    fun `bucket name is the last resort`() {
        assertEquals("Camera", VideoFolders.folderPath(null, null, "Camera"))
    }

    @Test
    fun `videos with no folder information are grouped apart`() {
        assertEquals(VideoFolders.UNKNOWN_PATH, VideoFolders.folderPath(null, null, null))
        assertEquals(VideoFolders.UNKNOWN_PATH, VideoFolders.folderPath("  ", "", "  "))
    }

    @Test
    fun `a file without a directory yields no path`() {
        assertEquals(VideoFolders.UNKNOWN_PATH, VideoFolders.folderPath("vid.mp4", null, null))
    }

    // ------------------------------------------------------------------ folder names ---

    @Test
    fun `folder name is the last path segment`() {
        assertEquals("Camera", VideoFolders.folderName("/storage/emulated/0/DCIM/Camera", null))
        assertEquals("Movies", VideoFolders.folderName("/storage/emulated/0/Movies/", null))
    }

    @Test
    fun `folder name falls back to the bucket then to a label`() {
        assertEquals("Downloads", VideoFolders.folderName("Downloads", "Downloads"))
        assertEquals("Downloads", VideoFolders.folderName("/", "Downloads"))
        assertEquals("Videos", VideoFolders.folderName("/", null))
        assertEquals("Other videos", VideoFolders.folderName(VideoFolders.UNKNOWN_PATH, null))
    }

    @Test
    fun `a video in the root of a volume gets a readable folder name`() {
        assertEquals("Internal storage", VideoFolders.folderName("/storage/emulated/0", "0"))
        assertEquals("SD card", VideoFolders.folderName("/storage/1A2B-3C4D", null))
    }

    @Test
    fun `a video in the root of a volume gets a readable breadcrumb`() {
        assertEquals("Internal storage", VideoFolders.breadcrumb("/storage/emulated/0"))
        assertEquals("SD card", VideoFolders.breadcrumb("/storage/1A2B-3C4D"))
    }

    @Test
    fun `root level videos are grouped under their volume`() {
        val folders = VideoFolders.group(
            listOf(video(1, "At the root", "/storage/emulated/0")),
            includeAll = false,
        )
        assertEquals("Internal storage", folders.single().name)
    }

    // --------------------------------------------------------------------- breadcrumbs ---

    @Test
    fun `breadcrumb shows the parent inside primary storage`() {
        assertEquals("DCIM", VideoFolders.breadcrumb("/storage/emulated/0/DCIM/Camera"))
        assertEquals("DCIM/Edited", VideoFolders.breadcrumb("/storage/emulated/0/DCIM/Edited/Final"))
    }

    @Test
    fun `breadcrumb names primary storage for top level folders`() {
        assertEquals("Internal storage", VideoFolders.breadcrumb("/storage/emulated/0/Movies"))
    }

    @Test
    fun `breadcrumb recognises removable volumes`() {
        assertEquals("SD card", VideoFolders.breadcrumb("/storage/1A2B-3C4D/Movies"))
        assertEquals("SD card/DCIM", VideoFolders.breadcrumb("/storage/1A2B-3C4D/DCIM/Camera"))
    }

    @Test
    fun `breadcrumb copes with fallback keys`() {
        assertEquals("Videos", VideoFolders.breadcrumb("Camera"))
        assertEquals("Uncategorised", VideoFolders.breadcrumb(VideoFolders.UNKNOWN_PATH))
    }

    // ------------------------------------------------------------------------ grouping ---

    @Test
    fun `videos are grouped by folder with all videos offered first`() {
        val videos = listOf(
            video(1, "Clip A", "/storage/emulated/0/DCIM/Camera"),
            video(2, "Clip B", "/storage/emulated/0/Movies"),
            video(3, "Clip C", "/storage/emulated/0/DCIM/Camera"),
        )

        val folders = VideoFolders.group(videos)

        assertEquals(
            listOf(
                VideoFolders.ALL_PATH,
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0/Movies",
            ),
            folders.map { it.path },
        )
        assertEquals(listOf("All videos", "Camera", "Movies"), folders.map { it.name })
        assertEquals(3, folders[0].videoCount)
        assertEquals("Every video on this device", folders[0].breadcrumb)

        val camera = folders[1]
        assertEquals("Camera", camera.name)
        assertEquals("DCIM", camera.breadcrumb)
        assertEquals(listOf(1L, 3L), camera.videos.map { it.id })
        assertEquals(2_000L, camera.totalBytes)
    }

    @Test
    fun `folders are sorted case insensitively by name`() {
        val videos = listOf(
            video(1, "a", "/storage/emulated/0/zebra"),
            video(2, "b", "/storage/emulated/0/Apple"),
            video(3, "c", "/storage/emulated/0/mango"),
        )

        assertEquals(
            listOf("All videos", "Apple", "mango", "zebra"),
            VideoFolders.group(videos).map { it.name },
        )
    }

    @Test
    fun `the newest video in a folder is used as the preview`() {
        // MediaStore returns rows newest first, and the list is kept in that order.
        val videos = listOf(
            video(10, "Newest", "/storage/emulated/0/DCIM/Camera"),
            video(9, "Older", "/storage/emulated/0/DCIM/Camera"),
        )

        val camera = VideoFolders.group(videos).first { !it.isAllVideos }
        assertEquals(10L, camera.preview?.id)
    }

    @Test
    fun `the all videos entry can be left out`() {
        val folders = VideoFolders.group(listOf(video(1, "Clip", "/storage/emulated/0/Movies")), includeAll = false)
        assertEquals(listOf("Movies"), folders.map { it.name })
    }

    @Test
    fun `an empty library produces no folders`() {
        assertTrue(VideoFolders.group(emptyList()).isEmpty())
        assertNull(VideoFolders.group(emptyList(), includeAll = false).firstOrNull())
    }

    @Test
    fun `videos without folder information get their own folder`() {
        val folders = VideoFolders.group(listOf(video(1, "Orphan", VideoFolders.UNKNOWN_PATH)))

        val other = folders.first { !it.isAllVideos }
        assertEquals("Other videos", other.name)
        assertEquals("Uncategorised", other.breadcrumb)
    }
}
