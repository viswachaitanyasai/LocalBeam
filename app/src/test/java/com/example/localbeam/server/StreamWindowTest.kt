package com.example.localbeam.server

import com.example.localbeam.data.model.LocalVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte/time maths behind a repositioned stream: the server tells the TV "this file
 * starts at 3:00", and these are the numbers that have to line up for playback to
 * resume in the right place.
 */
class StreamWindowTest {

    private companion object {
        const val TOTAL_BYTES = 1_000_000L
        const val DURATION_MS = 600_000L // ten minutes
    }

    private fun content(startSeconds: Long, durationMs: Long = DURATION_MS) = StreamContent(
        uri = "content://media/external/video/media/7",
        title = "Big Buck Bunny",
        mimeType = "video/x-matroska",
        sizeBytes = TOTAL_BYTES,
        durationMs = durationMs,
        extension = "mkv",
        startSeconds = startSeconds,
    )

    @Test
    fun `a normal cast exposes the whole file`() {
        val window = StreamWindow.of(content(0), TOTAL_BYTES)

        assertFalse(window.isWindowed)
        assertEquals(0L, window.offsetBytes)
        assertEquals(TOTAL_BYTES, window.length)
        assertEquals(DURATION_MS, window.remainingDurationMs)
    }

    @Test
    fun `a repositioned stream starts where the duration says`() {
        // Three minutes into a ten minute file: 30% of the bytes.
        val window = StreamWindow.of(content(180), TOTAL_BYTES)

        assertTrue(window.isWindowed)
        assertEquals(300_000L, window.offsetBytes)
        assertEquals(700_000L, window.length)
        assertEquals(180L, window.startSeconds)
        assertEquals(420_000L, window.remainingDurationMs)
        assertEquals(420L, window.remainingDurationSeconds)
    }

    @Test
    fun `positions and offsets map between the window and the file`() {
        val window = StreamWindow.of(content(180), TOTAL_BYTES)

        // The TV's byte 100 is really byte 300100 of the file.
        assertEquals(300_100L, window.realOffset(100))
        // …and the TV's second 1 is really second 181 of the film.
        assertEquals(181L, window.absoluteSeconds(1))
    }

    @Test
    fun `the window never runs past the end of the file`() {
        val window = StreamWindow.of(content(600), TOTAL_BYTES)

        // A seek to the very end still leaves one byte and a positive duration to serve.
        assertEquals(TOTAL_BYTES - 1, window.offsetBytes)
        assertEquals(1L, window.length)
        assertEquals(1_000L, window.remainingDurationMs)
    }

    @Test
    fun `without a duration the whole file is served`() {
        val window = StreamWindow.of(content(180, durationMs = 0), TOTAL_BYTES)

        assertFalse(window.isWindowed)
        assertEquals(0L, window.offsetBytes)
        assertEquals(TOTAL_BYTES, window.length)
    }

    @Test
    fun `an unknown file size cannot be windowed`() {
        val window = StreamWindow.of(content(180), totalBytes = 0)

        assertFalse(window.isWindowed)
        assertEquals(0L, window.length)
    }

    @Test
    fun `stream content describes the window the metadata is built from`() {
        val window = content(180).window()

        assertTrue(window.isWindowed)
        assertEquals(300_000L, window.offsetBytes)
        assertEquals(700_000L, window.length)
        assertEquals(420_000L, window.remainingDurationMs)
        assertEquals(420L, window.remainingDurationSeconds)
    }

    @Test
    fun `stream content can be built from a video and a resume position`() {
        val video = localVideo()

        val content = StreamContent.from(video, startSeconds = 120)

        assertEquals(video.uri, content.uri)
        assertEquals("video.mkv", content.fileName)
        assertEquals("/video.mkv", content.path)
        assertEquals(120L, content.startSeconds)
        assertEquals(200_000L, content.window().offsetBytes)

        // A renderer-supplied duration wins, because MediaStore often has none for MKV.
        val withRendererDuration = StreamContent.from(video, startSeconds = 0, durationMs = 300_000)
        assertEquals(300_000L, withRendererDuration.durationMs)
    }

    @Test
    fun `every stream gets a url of its own`() {
        val video = localVideo()
        val first = StreamContent.from(video, token = "a1b2c3d4")
        val second = StreamContent.from(video, token = "9f8e7d6c")

        // Renderers key their item state on the URL, and Samsung TVs refuse a URL they
        // have already played and stopped – so a reposition needs a new one.
        assertNotEquals(first.path, second.path)
        assertEquals("video-a1b2c3d4.mkv", first.fileName)
        // The extension still ends the path, because renderers sniff it.
        assertTrue(first.path.endsWith(".mkv"))
        assertEquals("video/x-matroska", first.contentType)
    }

    @Test
    fun `a stream without a token keeps the plain file name`() {
        assertEquals("/video.mkv", StreamContent.from(localVideo()).path)
    }

    private fun localVideo() = LocalVideo(
        id = 7,
        uri = "content://media/external/video/media/7",
        title = "Holiday",
        mimeType = "video/x-matroska",
        sizeBytes = TOTAL_BYTES,
        durationMs = DURATION_MS,
        folderPath = "/storage/emulated/0/Movies",
        folderName = "Movies",
    )
}
