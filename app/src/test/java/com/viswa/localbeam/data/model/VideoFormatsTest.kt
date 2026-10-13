package com.viswa.localbeam.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFormatsTest {

    @Test
    fun `maps known mime types to extensions`() {
        assertEquals("mp4", VideoFormats.extensionForMime("video/mp4"))
        assertEquals("mkv", VideoFormats.extensionForMime("video/x-matroska"))
        assertEquals("webm", VideoFormats.extensionForMime("video/webm"))
        assertEquals("3gp", VideoFormats.extensionForMime("video/3gpp"))
        assertEquals("avi", VideoFormats.extensionForMime("video/x-msvideo"))
    }

    @Test
    fun `tolerates parameters and casing in mime types`() {
        assertEquals("mp4", VideoFormats.extensionForMime("VIDEO/MP4; charset=utf-8"))
    }

    @Test
    fun `falls back to the file name then to mp4`() {
        // MediaStore happily reports application/octet-stream for videos on some ROMs.
        assertEquals("mkv", VideoFormats.extensionForMime("application/octet-stream", "Movie.mkv"))
        assertEquals("mp4", VideoFormats.extensionForMime("application/octet-stream", "no-extension"))
        assertEquals("mp4", VideoFormats.extensionForMime(null, null))
    }

    @Test
    fun `normalises a useless mime type into something a renderer accepts`() {
        assertEquals("video/mp4", VideoFormats.normalizeMime("application/octet-stream", "clip.mp4"))
        assertEquals("video/x-matroska", VideoFormats.normalizeMime("", "clip.mkv"))
        assertEquals("video/mp4", VideoFormats.normalizeMime("video/mp4"))
    }

    @Test
    fun `mime for extension round trips`() {
        VideoFormats.SUPPORTED_EXTENSIONS.forEach { extension ->
            val mime = VideoFormats.mimeForExtension(extension)
            assertTrue("$extension should map to a video mime", mime.startsWith("video/"))
        }
    }

    @Test
    fun `transport state mapping covers what renderers return`() {
        assertEquals(TransportState.PLAYING, TransportState.fromUpnp("PLAYING"))
        assertEquals(TransportState.PAUSED, TransportState.fromUpnp("PAUSED_PLAYBACK"))
        assertEquals(TransportState.PAUSED, TransportState.fromUpnp("PAUSED_RECORDING"))
        assertEquals(TransportState.STOPPED, TransportState.fromUpnp("STOPPED"))
        assertEquals(TransportState.TRANSITIONING, TransportState.fromUpnp("TRANSITIONING"))
        assertEquals(TransportState.NO_MEDIA, TransportState.fromUpnp("NO_MEDIA_PRESENT"))
        assertEquals(TransportState.UNKNOWN, TransportState.fromUpnp(null))
        assertEquals(TransportState.UNKNOWN, TransportState.fromUpnp("SOMETHING_NEW"))
    }

    @Test
    fun `only playing counts as playing`() {
        assertTrue(TransportState.PLAYING.isPlaying)
        assertFalse(TransportState.PAUSED.isPlaying)
        assertTrue(TransportState.PAUSED.isActive)
        assertFalse(TransportState.STOPPED.isActive)
    }
}
