package com.example.localbeam.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name in the video list has to be the file's own name. MediaStore's `TITLE` is not a
 * replacement for it: for files that carry container metadata it holds that metadata, and
 * a title tag of one word is common, which is how a list ends up showing "Big" for
 * "Big Buck Bunny 2010 1080p.mkv".
 */
class LocalVideoTest {

    private fun video(
        title: String,
        fileName: String = "",
        mimeType: String = "video/x-matroska",
    ) = LocalVideo(
        id = 1,
        uri = "content://media/external/video/media/1",
        title = title,
        mimeType = mimeType,
        sizeBytes = 700_000_000,
        durationMs = 5_400_000,
        folderPath = "/storage/emulated/0/Movies",
        folderName = "Movies",
        fileName = fileName,
    )

    @Test
    fun `the picker shows the file name without its extension`() {
        val video = video(title = "Big", fileName = "Big Buck Bunny 2010 1080p.mkv")

        assertEquals("Big Buck Bunny 2010 1080p", video.displayName)
    }

    @Test
    fun `a one-word metadata title never shortens the name`() {
        // The bug this guards: the row showed the TITLE column, so this listed as "Big".
        assertEquals(
            "Big Buck Bunny 2010 1080p BluRay x264",
            video(title = "Big", fileName = "Big Buck Bunny 2010 1080p BluRay x264.mkv").displayName,
        )
    }

    @Test
    fun `dots inside a name are kept, only the extension is dropped`() {
        assertEquals(
            "Big.Buck.Bunny.2010.1080p",
            video(title = "Big", fileName = "Big.Buck.Bunny.2010.1080p.mkv").displayName,
        )
    }

    @Test
    fun `falls back to the title when there is no file name`() {
        assertEquals("Holiday clip", video(title = "Holiday clip").displayName)
        // A name that is only a dot leaves nothing to show, and an extension-less name is
        // still a name.
        assertEquals("Holiday clip", video(title = "Holiday clip", fileName = ".nomedia").displayName)
        assertEquals("Holiday clip", video(title = "Holiday clip", fileName = "Holiday clip").displayName)
    }

    @Test
    fun `the extension comes from the file name when the mime type is useless`() {
        assertEquals("mkv", video(title = "Movie", fileName = "Movie.mkv").extension)
        assertEquals("mkv", video(title = "Movie", fileName = "Movie.mkv", mimeType = "").extension)
        // A MIME type still wins: it is what the server advertises.
        assertEquals("mp4", video(title = "Movie", fileName = "Movie.mkv", mimeType = "video/mp4").extension)
    }
}
