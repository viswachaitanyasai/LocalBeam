package com.example.localbeam.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Telling "playing" apart from "took the command and refused the media". Getting this
 * wrong in either direction is bad: a false refusal restarts the film behind the user's
 * back, a missed refusal leaves the TV stuck on its own error screen.
 */
class PlaybackStartWatchTest {

    private val watch = PlaybackStartWatch(graceMs = 2_000L)

    @Test
    fun `playing means it took the item`() {
        assertEquals(true, watch.observe(TransportState.PLAYING, 0L))
    }

    @Test
    fun `paused also means it took the item`() {
        assertEquals(true, watch.observe(TransportState.PAUSED, 0L))
    }

    @Test
    fun `a stopped renderer gets time to start before it is called a refusal`() {
        // Right after SetAVTransportURI a renderer reports STOPPED for a moment…
        assertNull(watch.observe(TransportState.STOPPED, 0L))
        assertNull(watch.observe(TransportState.STOPPED, 1_000L))
        // …and then starts, well inside the grace period.
        assertEquals(true, watch.observe(TransportState.PLAYING, 1_500L))
    }

    @Test
    fun `staying stopped past the grace period is a refusal`() {
        assertNull(watch.observe(TransportState.STOPPED, 0L))
        assertEquals(false, watch.observe(TransportState.STOPPED, 2_000L))
    }

    @Test
    fun `no media counts the same as stopped`() {
        assertNull(watch.observe(TransportState.NO_MEDIA, 0L))
        assertEquals(false, watch.observe(TransportState.NO_MEDIA, 4_000L))
    }

    @Test
    fun `buffering clears the stop`() {
        assertNull(watch.observe(TransportState.STOPPED, 0L))
        // TRANSITIONING proves the item was accepted, so the earlier STOPPED no longer counts.
        assertNull(watch.observe(TransportState.TRANSITIONING, 1_000L))
        assertNull(watch.observe(TransportState.STOPPED, 2_500L))
        assertEquals(true, watch.observe(TransportState.PLAYING, 2_600L))
    }

    @Test
    fun `an unhelpful renderer is never called a refusal`() {
        assertNull(watch.observe(TransportState.UNKNOWN, 0L))
        assertNull(watch.observe(TransportState.UNKNOWN, 10_000L))
    }
}
