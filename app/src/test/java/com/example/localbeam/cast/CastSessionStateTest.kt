package com.example.localbeam.cast

import com.example.localbeam.data.model.DlnaDevice
import com.example.localbeam.data.model.LocalVideo
import com.example.localbeam.data.model.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session state is what the remote screen renders, and it decides whether entering
 * that screen has to start (or switch) a stream.
 */
class CastSessionStateTest {

    private val device = DlnaDevice(
        friendlyName = "Living room TV",
        controlUrl = "http://192.168.43.2:9197/upnp/control/AVTransport1",
        location = "http://192.168.43.2:9197/dmr",
    )

    private fun video(id: Long, uri: String) = LocalVideo(
        id = id,
        uri = uri,
        title = "Video $id",
        mimeType = "video/x-matroska",
        sizeBytes = 700_000_000,
        durationMs = 5_400_000,
        folderPath = "/storage/emulated/0/Movies",
        folderName = "Movies",
    )

    private val first = video(1, "content://media/external/video/media/1")
    private val second = video(2, "content://media/external/video/media/2")

    // -------------------------------------------------------------- switching ---

    @Test
    fun `a selection with nothing on the tv has to start`() {
        val state = CastSessionState(device = device, video = first)

        assertTrue(state.needsStart)
        assertFalse(state.isCasting)
    }

    @Test
    fun `the video already playing does not restart`() {
        val state = CastSessionState(
            device = device,
            video = first,
            serverUrl = "http://192.168.43.1:8080/video.mkv",
            castingVideoUri = first.uri,
        )

        assertFalse(state.needsStart)
    }

    @Test
    fun `picking another video while one is playing switches the stream`() {
        // The bug this guards: with only `isCasting` to go on, the remote screen decided
        // there was nothing to do and the TV kept playing the first video.
        val state = CastSessionState(
            device = device,
            video = second,
            serverUrl = "http://192.168.43.1:8080/video.mkv",
            castingVideoUri = first.uri,
        )

        assertTrue(state.needsStart)
    }

    @Test
    fun `a start in flight is not started twice`() {
        val state = CastSessionState(
            device = device,
            video = first,
            serverUrl = "http://192.168.43.1:8080/video.mkv",
            castingVideoUri = second.uri,
            isStarting = true,
        )

        assertFalse(state.needsStart)
    }

    @Test
    fun `nothing selected means nothing to start`() {
        assertFalse(CastSessionState(device = device).needsStart)
    }

    // ---------------------------------------------------------------- reading ---

    @Test
    fun `duration prefers the renderer and falls back to mediastore`() {
        val fromRenderer = CastSessionState(device = device, video = first, durationSeconds = 5_400)
        assertEquals(5_400L, fromRenderer.effectiveDurationSeconds)

        val fromLibrary = CastSessionState(device = device, video = first)
        assertEquals(5_400L, fromLibrary.effectiveDurationSeconds)

        assertEquals(0L, CastSessionState(device = device).effectiveDurationSeconds)
    }

    @Test
    fun `a repositioned stream carries the offset its positions are measured from`() {
        // A repositioned stream reports 0:00 at its start, so the app needs to know where
        // that stream began in order to show a position in the film.
        val state = CastSessionState(
            device = device,
            video = first,
            serverUrl = "http://192.168.43.1:8080/video.mkv",
            castingVideoUri = first.uri,
            streamStartSeconds = 1_200,
            positionSeconds = 1_200,
            transportState = TransportState.PLAYING,
        )

        assertEquals(1_200L, state.streamStartSeconds)
        assertEquals(TransportState.PLAYING, state.transportState)
    }
}
